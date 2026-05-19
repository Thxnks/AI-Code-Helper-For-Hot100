package com.yupi.aicodehelper.agent;

import java.util.List;

public record SearchFilter(String difficulty, List<String> tags, String pattern, String tenantId) {

    public static SearchFilter none() {
        return new SearchFilter(null, List.of(), null, null);
    }

    public boolean isEmpty() {
        return (difficulty == null || difficulty.isBlank())
                && (tags == null || tags.isEmpty())
                && (pattern == null || pattern.isBlank())
                && (tenantId == null || tenantId.isBlank());
    }

    public boolean matches(String chunkDifficulty, List<String> chunkTags,
                           String chunkPattern, String chunkTenant) {
        if (difficulty != null && !difficulty.isBlank()
                && !difficulty.equalsIgnoreCase(chunkDifficulty)) {
            return false;
        }
        if (tags != null && !tags.isEmpty()) {
            boolean hasTag = false;
            for (String tag : tags) {
                if (chunkTags != null && chunkTags.stream()
                        .anyMatch(t -> t.equalsIgnoreCase(tag))) {
                    hasTag = true;
                    break;
                }
            }
            if (!hasTag) return false;
        }
        if (pattern != null && !pattern.isBlank()
                && !pattern.equalsIgnoreCase(chunkPattern)) {
            return false;
        }
        if (tenantId != null && !tenantId.isBlank()
                && !tenantId.equals(chunkTenant == null ? "" : chunkTenant)) {
            return false;
        }
        return true;
    }
}
