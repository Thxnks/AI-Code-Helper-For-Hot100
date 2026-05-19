package com.yupi.aicodehelper.ai.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Redis-backed vector store for Agent knowledge RAG.
 * Stores embeddings persistently in Redis and supports cosine similarity search.
 * Works alongside (not replacing) the LangChain4j InMemoryEmbeddingStore pipeline.
 */
public class RedisEmbeddingStore {

    private static final String KEY_PREFIX = "rag:emb:";
    private static final String INDEX_KEY = "rag:emb:ids";
    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisEmbeddingStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void add(String id, float[] vector, TextSegment segment) {
        StoredEntry entry = new StoredEntry(id, vector, segment);
        try {
            redis.opsForValue().set(KEY_PREFIX + id, objectMapper.writeValueAsString(entry));
            redis.opsForSet().add(INDEX_KEY, id);
        } catch (JsonProcessingException ignored) {
        }
    }

    public void removeAll() {
        try {
            Set<String> ids = redis.opsForSet().members(INDEX_KEY);
            if (ids != null && !ids.isEmpty()) {
                redis.delete(ids.stream().map(k -> KEY_PREFIX + k).toList());
            }
            redis.delete(INDEX_KEY);
        } catch (Exception ignored) {
        }
    }

    public boolean isEmpty() {
        try {
            Long size = redis.opsForSet().size(INDEX_KEY);
            return size == null || size == 0;
        } catch (Exception e) {
            return true;
        }
    }

    public record SearchResult(String id, double score, String text,
                                String fileName, String difficulty,
                                String tags, String pattern) {
    }

    public List<SearchResult> search(float[] queryVector, int maxResults, double minScore) {
        try {
            Set<String> ids = redis.opsForSet().members(INDEX_KEY);
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }

            List<String> keyList = new ArrayList<>(ids);
            List<String> prefixedKeys = keyList.stream().map(k -> KEY_PREFIX + k).toList();
            List<String> rawList = redis.opsForValue().multiGet(prefixedKeys);

            List<SearchResult> results = new ArrayList<>();
            for (String raw : rawList) {
                if (raw == null) continue;
                try {
                    StoredEntry entry = objectMapper.readValue(raw, StoredEntry.class);
                    if (entry.vector == null || entry.vector.length == 0) continue;
                    double sim = cosineSimilarity(queryVector, entry.vector);
                    if (sim < minScore) continue;
                    results.add(new SearchResult(entry.id, sim, entry.text,
                            entry.fileName == null ? "" : entry.fileName,
                            entry.difficulty == null ? "" : entry.difficulty,
                            entry.tags == null ? "" : entry.tags,
                            entry.pattern == null ? "" : entry.pattern));
                } catch (Exception ignored) {
                }
            }
            results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
            return results.subList(0, Math.min(results.size(), maxResults));
        } catch (Exception e) {
            return List.of();
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public static class StoredEntry {
        public String id;
        public float[] vector;
        public String text;
        public String fileName;
        public String difficulty;
        public String tags;
        public String pattern;

        public StoredEntry() {}

        StoredEntry(String id, float[] vector, TextSegment segment) {
            this.id = id;
            this.vector = vector;
            this.text = segment == null ? "" : segment.text();
            if (segment != null && segment.metadata() != null) {
                var meta = segment.metadata();
                this.fileName = meta.getString("file_name");
                this.difficulty = meta.getString("difficulty");
                this.tags = meta.getString("tags");
                this.pattern = meta.getString("pattern");
            }
        }
    }
}
