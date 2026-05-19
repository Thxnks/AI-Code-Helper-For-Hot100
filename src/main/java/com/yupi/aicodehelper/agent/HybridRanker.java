package com.yupi.aicodehelper.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class HybridRanker {

    private static final double VECTOR_WEIGHT = 0.6;
    private static final double KEYWORD_WEIGHT = 0.4;
    private static final int MAX_CONTENT_LENGTH = 700;

    private final Map<String, KnowledgeChunk> chunkIndex;

    public HybridRanker(List<KnowledgeChunk> chunks) {
        this.chunkIndex = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : chunks) {
            chunkIndex.put(chunk.source(), chunk);
        }
    }

    public List<KnowledgeSnippetView> rank(String query,
                                           List<KnowledgeSnippetView> vectorResults,
                                           int limit) {
        List<String> terms = splitTerms(query);
        if (terms.isEmpty()) {
            return vectorResults.stream().limit(limit).toList();
        }

        Map<String, Double> vectorScores = new LinkedHashMap<>();
        for (KnowledgeSnippetView v : vectorResults) {
            String key = v.source() != null ? v.source() : "";
            vectorScores.put(key, v.score() != null ? v.score().doubleValue() / 100.0 : 0.0);
        }

        List<ScoredChunk> merged = new ArrayList<>();
        for (KnowledgeChunk chunk : chunkIndex.values()) {
            String key = chunk.source();
            double vs = vectorScores.getOrDefault(key, 0.0);
            double ks = normalizedKeywordScore(chunk, terms);
            double finalScore = vs * VECTOR_WEIGHT + ks * KEYWORD_WEIGHT;
            if (finalScore > 0) {
                List<String> matched = matchedTerms(chunk, terms);
                merged.add(new ScoredChunk(chunk, finalScore, matched, vs, ks));
            }
        }

        // Add vector results that have no local chunk
        for (KnowledgeSnippetView v : vectorResults) {
            String src = v.source();
            if (src == null) continue;
            if (merged.stream().noneMatch(m -> m.chunk.source().equals(src))) {
                List<String> matched = List.of();
                merged.add(new ScoredChunk(
                        new KnowledgeChunk(src, v.slug() == null ? "" : v.slug(),
                                v.title() == null ? "" : v.title(),
                                v.section() == null ? "" : v.section(),
                                v.content() == null ? "" : v.content()),
                        v.score() != null ? v.score() / 100.0 : 0.0,
                        matched, v.score() != null ? v.score() / 100.0 : 0.0, 0.0));
            }
        }

        merged.sort(Comparator.comparingDouble(ScoredChunk::finalScore).reversed());

        return merged.stream()
                .limit(limit)
                .map(sc -> new KnowledgeSnippetView(
                        sc.chunk.source(),
                        sc.chunk.slug(),
                        sc.chunk.title(),
                        sc.chunk.section(),
                        (int) Math.round(sc.finalScore * 100),
                        sc.matchedTerms(),
                        truncate(bestWindow(sc.chunk.content(), terms), MAX_CONTENT_LENGTH)))
                .toList();
    }

    private double normalizedKeywordScore(KnowledgeChunk chunk, List<String> terms) {
        String normalized = chunk.content().toLowerCase(Locale.ROOT);
        String normalizedSection = chunk.section().toLowerCase(Locale.ROOT);
        String normalizedTitle = chunk.title().toLowerCase(Locale.ROOT);
        String normalizedSlug = chunk.slug().toLowerCase(Locale.ROOT);
        double score = 0;
        for (String term : terms) {
            int occurrences = countOccurrences(normalized, term);
            score += Math.min(occurrences, 5) * 0.2;
            if (normalizedTitle.contains(term) || normalizedSlug.contains(term)) {
                score += 1.0;
            }
            if (normalizedSection.contains(term)) {
                score += 0.6;
            }
        }
        return Math.tanh(score);
    }

    private List<String> matchedTerms(KnowledgeChunk chunk, List<String> terms) {
        String normalized = chunk.content().toLowerCase(Locale.ROOT);
        return terms.stream()
                .filter(t -> normalized.contains(t)
                        || chunk.title().toLowerCase(Locale.ROOT).contains(t)
                        || chunk.slug().toLowerCase(Locale.ROOT).contains(t)
                        || chunk.section().toLowerCase(Locale.ROOT).contains(t))
                .collect(Collectors.toList());
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

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record ScoredChunk(KnowledgeChunk chunk, double finalScore,
                               List<String> matchedTerms, double vectorScore, double keywordScore) {
    }

    public record KnowledgeChunk(String source, String slug, String title,
                                  String section, String content) {
    }
}
