package com.yupi.aicodehelper.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.aicodehelper.ai.rag.RedisEmbeddingStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentKnowledgeService {

    private static final int MAX_CONTENT_LENGTH = 700;
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,3})\\s+(.+)$");

    private final ObjectProvider<ContentRetriever> contentRetrieverProvider;
    private final ObjectProvider<RedisEmbeddingStore> redisStoreProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final ObjectMapper objectMapper;

    private volatile List<KnowledgeChunk> localChunks;

    public AgentKnowledgeService(ObjectProvider<ContentRetriever> contentRetrieverProvider,
                                 ObjectMapper objectMapper) {
        this.contentRetrieverProvider = contentRetrieverProvider;
        this.redisStoreProvider = null;
        this.embeddingModelProvider = null;
        this.objectMapper = objectMapper;
    }

    @Autowired
    public AgentKnowledgeService(ObjectProvider<ContentRetriever> contentRetrieverProvider,
                                 ObjectProvider<RedisEmbeddingStore> redisStoreProvider,
                                 ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                 ObjectMapper objectMapper) {
        this.contentRetrieverProvider = contentRetrieverProvider;
        this.redisStoreProvider = redisStoreProvider;
        this.embeddingModelProvider = embeddingModelProvider;
        this.objectMapper = objectMapper;
    }

    public List<KnowledgeSnippetView> retrieve(String query, int limit) {
        return retrieve(query, limit, SearchFilter.none());
    }

    public List<KnowledgeSnippetView> retrieve(String query, int limit, SearchFilter filter) {
        int realLimit = Math.max(1, Math.min(limit, 8));
        List<KnowledgeSnippetView> vectorResults = retrieveWithVectorStore(query, realLimit * 2, filter);
        List<KnowledgeSnippetView> keywordResults = retrieveWithLocalChunks(query, realLimit * 2, filter);

        if (!vectorResults.isEmpty() || !keywordResults.isEmpty()) {
            List<HybridRanker.KnowledgeChunk> chunks = localChunks().stream()
                    .map(kc -> new HybridRanker.KnowledgeChunk(
                            kc.source(), kc.slug(), kc.title(), kc.section(), kc.content()))
                    .toList();
            HybridRanker ranker = new HybridRanker(chunks);
            return ranker.rank(query, vectorResults, realLimit);
        }
        return List.of();
    }

    private List<KnowledgeSnippetView> retrieveWithVectorStore(String query, int limit, SearchFilter filter) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        // Prefer Redis (persistent, fast cold start). Fall back to InMemory if Redis unavailable.
        RedisEmbeddingStore redisStore = redisStoreProvider != null ? redisStoreProvider.getIfAvailable() : null;
        EmbeddingModel embeddingModel = embeddingModelProvider != null ? embeddingModelProvider.getIfAvailable() : null;
        if (redisStore != null && embeddingModel != null && !redisStore.isEmpty()) {
            try {
                Embedding queryEmbedding = embeddingModel.embed(query).content();
                double adjustedMinScore = dynamicMinScore(query);
                return redisStore.search(queryEmbedding.vector(), limit, adjustedMinScore).stream()
                        .filter(r -> filter.isEmpty()
                                || filter.matches(r.difficulty(),
                                        r.tags() != null && !r.tags().isBlank()
                                                ? List.of(r.tags().split(",\\s*"))
                                                : List.of(),
                                        r.pattern(), null))
                        .map(r -> new KnowledgeSnippetView(
                                r.fileName(), null, null, null,
                                (int) Math.round(r.score() * 100),
                                List.of(),
                                truncate(r.text(), MAX_CONTENT_LENGTH)))
                        .toList();
            } catch (Exception ignored) {
            }
        }

        // Fallback: LangChain4j InMemoryEmbeddingStore
        ContentRetriever contentRetriever = contentRetrieverProvider.getIfAvailable();
        if (contentRetriever != null) {
            try {
                double adjustedMinScore = dynamicMinScore(query);
                return contentRetriever.retrieve(Query.from(query)).stream()
                        .filter(c -> passesFilter(c, filter))
                        .limit(limit)
                        .map(this::toSnippet)
                        .toList();
            } catch (Exception ignored) {
            }
        }
        return List.of();
    }

    private double dynamicMinScore(String query) {
        int termCount = splitTerms(query).size();
        if (termCount <= 1) return 0.55;
        if (termCount <= 2) return 0.50;
        return 0.45;
    }

    private boolean passesFilter(Content content, SearchFilter filter) {
        if (filter.isEmpty()) return true;
        if (content.textSegment() == null) return true;
        var meta = content.textSegment().metadata();
        String difficulty = meta.getString("difficulty");
        String tagsStr = meta.getString("tags");
        List<String> tags = tagsStr != null && !tagsStr.isBlank()
                ? List.of(tagsStr.split(",\\s*"))
                : List.of();
        String pattern = meta.getString("pattern");
        String tenant = meta.getString("tenant");
        return filter.matches(difficulty, tags, pattern, tenant);
    }

    private KnowledgeSnippetView toSnippet(Content content) {
        String source = "vector-store";
        String difficulty = null;
        String tags = null;
        String pattern = null;
        if (content.textSegment() != null && content.textSegment().metadata() != null) {
            var meta = content.textSegment().metadata();
            String fileName = meta.getString("file_name");
            if (fileName != null && !fileName.isBlank()) {
                source = fileName;
            }
            difficulty = meta.getString("difficulty");
            tags = meta.getString("tags");
            pattern = meta.getString("pattern");
        }
        String text = content.textSegment() == null ? "" : content.textSegment().text();
        return new KnowledgeSnippetView(source, null, null, null, null,
                tagsToList(tags), truncate(text, MAX_CONTENT_LENGTH));
    }

    private List<String> tagsToList(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        return List.of(tags.split(",\\s*"));
    }

    private List<KnowledgeSnippetView> retrieveWithLocalChunks(String query, int limit, SearchFilter filter) {
        List<String> terms = splitTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        return localChunks().stream()
                .filter(chunk -> passesLocalFilter(chunk, filter))
                .map(chunk -> new ScoredChunk(chunk, score(chunk, terms), matchedTerms(chunk, terms)))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingInt(ScoredChunk::score).reversed()
                        .thenComparing(item -> item.chunk().source()))
                .limit(limit)
                .map(item -> toSnippet(item, terms))
                .toList();
    }

    private boolean passesLocalFilter(KnowledgeChunk chunk, SearchFilter filter) {
        if (filter.isEmpty()) return true;
        String mappedDifficulty = mapDifficulty(chunk.difficulty());
        String mappedPattern = chunk.pattern();
        return filter.matches(mappedDifficulty, chunk.tags(), mappedPattern, null);
    }

    private String mapDifficulty(String d) {
        return d == null ? "" : d;
    }

    private KnowledgeSnippetView toSnippet(ScoredChunk item, List<String> terms) {
        KnowledgeChunk chunk = item.chunk();
        return new KnowledgeSnippetView(
                chunk.source(),
                chunk.slug(),
                chunk.title(),
                chunk.section(),
                item.score(),
                item.matchedTerms(),
                truncate(bestWindow(chunk.content(), terms), MAX_CONTENT_LENGTH)
        );
    }

    private List<KnowledgeChunk> localChunks() {
        List<KnowledgeChunk> snapshot = localChunks;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (localChunks == null) {
                localChunks = loadLocalChunks();
            }
            return localChunks;
        }
    }

    private List<KnowledgeChunk> loadLocalChunks() {
        Map<String, ProblemMetadata> metadataBySlug = loadProblemMetadata();
        List<KnowledgeChunk> chunks = new ArrayList<>();
        chunks.addAll(loadMarkdownChunks("classpath:hot100/markdown/*.md", metadataBySlug));
        chunks.addAll(loadMarkdownChunks("classpath:docs/**/*.md", Map.of()));
        return chunks;
    }

    private Map<String, ProblemMetadata> loadProblemMetadata() {
        try {
            ResourcePatternResolver resolver = ResourcePatternUtils.getResourcePatternResolver(null);
            Resource[] resources = resolver.getResources("classpath:hot100/json/*.json");
            Map<String, ProblemMetadata> metadataBySlug = new LinkedHashMap<>();
            for (Resource resource : resources) {
                if (!resource.exists() || !resource.isReadable()) {
                    continue;
                }
                try {
                    String raw = stripBom(StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8));
                    JsonNode root = objectMapper.readTree(raw);
                    String slug = root.path("slug").asText(fileStem(resource.getFilename()));
                    metadataBySlug.put(slug, new ProblemMetadata(
                            slug,
                            root.path("title").asText(""),
                            root.path("difficulty").asText(""),
                            toStringList(root.path("tags")),
                            root.path("pattern").asText(""),
                            root.path("summary").asText("")
                    ));
                } catch (Exception ignored) {
                }
            }
            return metadataBySlug;
        } catch (IOException e) {
            return Map.of();
        }
    }

    private List<KnowledgeChunk> loadMarkdownChunks(String pattern, Map<String, ProblemMetadata> metadataBySlug) {
        try {
            ResourcePatternResolver resolver = ResourcePatternUtils.getResourcePatternResolver(null);
            Resource[] resources = resolver.getResources(pattern);
            List<KnowledgeChunk> chunks = new ArrayList<>();
            for (Resource resource : resources) {
                if (!resource.exists() || !resource.isReadable()) {
                    continue;
                }
                String filename = resource.getFilename();
                if (filename == null || filename.isBlank()) {
                    continue;
                }
                String content = stripBom(StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8));
                if (!content.isBlank()) {
                    chunks.addAll(splitMarkdown(filename, content, metadataBySlug.get(fileStem(filename))));
                }
            }
            return chunks;
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<KnowledgeChunk> splitMarkdown(String filename, String markdown, ProblemMetadata metadata) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        String slug = metadata == null ? fileStem(filename) : metadata.slug();
        String title = metadata == null ? "" : metadata.title();
        String section = "document";
        StringBuilder sectionContent = new StringBuilder();
        int chunkIndex = 1;
        for (String line : markdown.split("\\R")) {
            Matcher matcher = MARKDOWN_HEADING.matcher(line);
            if (matcher.matches()) {
                chunkIndex = flushChunk(chunks, filename, slug, title, section,
                        sectionContent, metadata, chunkIndex);
                section = matcher.group(2).trim();
            }
            sectionContent.append(line).append('\n');
        }
        flushChunk(chunks, filename, slug, title, section, sectionContent, metadata, chunkIndex);
        return chunks;
    }

    private int flushChunk(List<KnowledgeChunk> chunks,
                           String filename,
                           String slug,
                           String title,
                           String section,
                           StringBuilder sectionContent,
                           ProblemMetadata metadata,
                           int chunkIndex) {
        String content = sectionContent.toString().trim();
        sectionContent.setLength(0);
        if (content.isBlank()) {
            return chunkIndex;
        }
        String metadataText = metadata == null ? "" : """
                title: %s
                difficulty: %s
                tags: %s
                pattern: %s
                summary: %s
                """.formatted(metadata.title(), metadata.difficulty(), String.join(" ", metadata.tags()),
                metadata.pattern(), metadata.summary()).trim();
        String searchableContent = metadataText.isBlank() ? content : metadataText + "\n\n" + content;
        chunks.add(new KnowledgeChunk(
                filename + "#" + chunkIndex,
                slug,
                title,
                section,
                content,
                metadata == null ? "" : metadata.difficulty(),
                metadata == null ? List.<String>of() : metadata.tags(),
                metadata == null ? "" : metadata.pattern(),
                searchableContent
        ));
        return chunkIndex + 1;
    }

    private List<String> splitTerms(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", " ");
        List<String> terms = new ArrayList<>();
        for (String term : normalized.split("\\s+")) {
            if (term.length() >= 2) {
                terms.add(term);
            }
        }
        return terms.stream().distinct().toList();
    }

    private int score(KnowledgeChunk chunk, List<String> terms) {
        String normalized = chunk.content().toLowerCase(Locale.ROOT);
        String normalizedSection = chunk.section().toLowerCase(Locale.ROOT);
        String normalizedTitle = chunk.title().toLowerCase(Locale.ROOT);
        String normalizedSlug = chunk.slug().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            int occurrences = countOccurrences(normalized, term);
            score += occurrences;
            if (normalizedTitle.contains(term) || normalizedSlug.contains(term)) {
                score += 8;
            }
            if (normalizedSection.contains(term)) {
                score += 4;
            }
        }
        return score;
    }

    private List<String> matchedTerms(KnowledgeChunk chunk, List<String> terms) {
        String normalized = chunk.content().toLowerCase(Locale.ROOT);
        Set<String> matched = new LinkedHashSet<>();
        for (String term : terms) {
            if (normalized.contains(term)
                    || chunk.title().toLowerCase(Locale.ROOT).contains(term)
                    || chunk.slug().toLowerCase(Locale.ROOT).contains(term)
                    || chunk.section().toLowerCase(Locale.ROOT).contains(term)) {
                matched.add(term);
            }
        }
        return List.copyOf(matched);
    }

    private int countOccurrences(String content, String term) {
        int score = 0;
        int index = content.indexOf(term);
        while (index >= 0) {
            score++;
            index = content.indexOf(term, index + term.length());
        }
        return score;
    }

    private String bestWindow(String content, List<String> terms) {
        String normalized = content.toLowerCase(Locale.ROOT);
        int bestIndex = 0;
        for (String term : terms) {
            int index = normalized.indexOf(term);
            if (index >= 0) {
                bestIndex = index;
                break;
            }
        }
        int start = Math.max(0, bestIndex - 120);
        int end = Math.min(content.length(), bestIndex + MAX_CONTENT_LENGTH);
        return content.substring(start, end).trim();
    }

    private List<String> toStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private String fileStem(String filename) {
        if (filename == null) {
            return "";
        }
        int index = filename.lastIndexOf('.');
        return index <= 0 ? filename : filename.substring(0, index);
    }

    private String stripBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == '﻿') {
            return value.substring(1);
        }
        return value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record ProblemMetadata(String slug,
                                   String title,
                                   String difficulty,
                                   List<String> tags,
                                   String pattern,
                                   String summary) {
    }

    // Extended KnowledgeChunk including metadata fields for filtering
    private record KnowledgeChunk(String source,
                                  String slug,
                                  String title,
                                  String section,
                                  String content,
                                  String difficulty,
                                  List<String> tags,
                                  String pattern,
                                  String searchableContent) {
        KnowledgeChunk(String source, String slug, String title, String section,
                       String content, String difficulty, List<String> tags,
                       String pattern, String searchableContent) {
            this.source = source;
            this.slug = slug;
            this.title = title;
            this.section = section;
            this.content = content;
            this.difficulty = difficulty;
            this.tags = tags;
            this.pattern = pattern;
            this.searchableContent = searchableContent;
        }

        // Backward-compat: when metadata is not available
        KnowledgeChunk(String source, String slug, String title, String section, String content) {
            this(source, slug, title, section, content, "", List.of(), "", content);
        }
    }

    private record ScoredChunk(KnowledgeChunk chunk, int score, List<String> matchedTerms) {
    }
}
