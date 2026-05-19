package com.yupi.aicodehelper.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
public class RagConfig {

    private static final String RAG_VERSION_KEY = "rag:version";
    private static final int RAG_VERSION = 2;

    @Resource
    private EmbeddingModel qwenEmbeddingModel;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Bean
    public RedisEmbeddingStore redisEmbeddingStore() {
        return new RedisEmbeddingStore(stringRedisTemplate, objectMapper);
    }

    @Bean
    public ContentRetriever contentRetriever() {
        List<Document> documents = new ArrayList<>();
        documents.addAll(loadClasspathDocuments("classpath:docs/**/*"));
        documents.addAll(loadHot100Documents());

        DocumentByParagraphSplitter paragraphSplitter = new DocumentByParagraphSplitter(1000, 200);

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(paragraphSplitter)
                .textSegmentTransformer(textSegment -> TextSegment.from(
                        textSegment.metadata().getString("file_name") + "\n" + textSegment.text(),
                        textSegment.metadata()
                ))
                .embeddingModel(qwenEmbeddingModel)
                .embeddingStore(embeddingStore)
                .build();
        ingestor.ingest(documents);

        // Persist embeddings to Redis, rebuild when version changes or empty
        RedisEmbeddingStore redisStore = redisEmbeddingStore();
        String storedVersion = stringRedisTemplate.opsForValue().get(RAG_VERSION_KEY);
        boolean needsRebuild = !String.valueOf(RAG_VERSION).equals(storedVersion);
        if (needsRebuild) {
            redisStore.removeAll();
        }
        if (needsRebuild || redisStore.isEmpty()) {
            for (Document doc : documents) {
                List<TextSegment> segments = paragraphSplitter.split(doc);
                for (int i = 0; i < segments.size(); i++) {
                    TextSegment seg = segments.get(i);
                    dev.langchain4j.data.embedding.Embedding emb = qwenEmbeddingModel.embed(seg).content();
                    redisStore.add(doc.metadata().getString("file_name") + "#" + i,
                            emb.vector(), seg);
                }
            }
            stringRedisTemplate.opsForValue().set(RAG_VERSION_KEY, String.valueOf(RAG_VERSION));
        }

        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(qwenEmbeddingModel)
                .maxResults(8)
                .minScore(0.6)
                .build();
    }

    private List<Document> loadHot100Documents() {
        try {
            ResourcePatternResolver resolver = ResourcePatternUtils.getResourcePatternResolver(null);
            org.springframework.core.io.Resource[] jsonResources = resolver.getResources("classpath:hot100/json/*.json");
            Map<String, JsonNode> metadataBySlug = new LinkedHashMap<>();
            for (org.springframework.core.io.Resource resource : jsonResources) {
                if (!resource.exists() || !resource.isReadable()) continue;
                String raw = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                JsonNode root = objectMapper.readTree(raw);
                String slug = root.path("slug").asText(fileStem(resource.getFilename()));
                metadataBySlug.put(slug, root);
            }

            org.springframework.core.io.Resource[] mdResources = resolver.getResources("classpath:hot100/markdown/*.md");
            List<Document> documents = new ArrayList<>();
            for (org.springframework.core.io.Resource resource : mdResources) {
                if (!resource.exists() || !resource.isReadable()) continue;
                String filename = resource.getFilename();
                if (filename == null || filename.isBlank()) continue;
                String text = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                if (text.isBlank()) continue;

                String slug = fileStem(filename);
                JsonNode meta = metadataBySlug.get(slug);

                Metadata metadata = Metadata.from(Document.FILE_NAME, filename);
                if (meta != null) {
                    metadata.put("difficulty", meta.path("difficulty").asText(""));
                    metadata.put("tags", String.join(",", toStringList(meta.path("tags"))));
                    metadata.put("pattern", meta.path("pattern").asText(""));
                    metadata.put("slug", slug);
                }
                documents.add(Document.from(text, metadata));
            }
            return documents;
        } catch (IOException e) {
            return List.of();
        }
    }

    private String fileStem(String filename) {
        if (filename == null) return "";
        int index = filename.lastIndexOf('.');
        return index <= 0 ? filename : filename.substring(0, index);
    }

    private List<String> toStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("");
            if (!value.isBlank()) values.add(value);
        }
        return values;
    }

    private List<Document> loadClasspathDocuments(String resourcePattern) {
        try {
            ResourcePatternResolver resolver = ResourcePatternUtils.getResourcePatternResolver(null);
            org.springframework.core.io.Resource[] resources = resolver.getResources(resourcePattern);
            List<Document> documents = new ArrayList<>();
            for (org.springframework.core.io.Resource resource : resources) {
                if (!resource.exists() || !resource.isReadable()) {
                    continue;
                }
                String filename = resource.getFilename();
                if (filename == null || filename.isBlank()) {
                    continue;
                }
                String text = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                if (text.isBlank()) {
                    continue;
                }
                Metadata metadata = Metadata.from(Document.FILE_NAME, filename);
                documents.add(Document.from(text, metadata));
            }
            return documents;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load RAG documents from classpath", e);
        }
    }
}
