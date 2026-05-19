package com.yupi.aicodehelper.agent.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

public class ToolIdempotencyGuard {

    private static final String KEY_PREFIX = "tool:idem:";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final HexFormat HEX = HexFormat.of();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ToolIdempotencyGuard(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public boolean isDuplicate(String toolName, Map<String, Object> input) {
        if (redis == null) return false;
        String hash = hashCall(toolName, input);
        if (hash == null) return false;
        try {
            String key = KEY_PREFIX + hash;
            Boolean exists = redis.hasKey(key);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            return false;
        }
    }

    public String getCachedResult(String toolName, Map<String, Object> input) {
        if (redis == null) return null;
        String hash = hashCall(toolName, input);
        if (hash == null) return null;
        try {
            return redis.opsForValue().get(KEY_PREFIX + hash);
        } catch (Exception e) {
            return null;
        }
    }

    public void markExecuted(String toolName, Map<String, Object> input, Object result) {
        if (redis == null) return;
        String hash = hashCall(toolName, input);
        if (hash == null) return;
        try {
            String resultJson = result instanceof String ? (String) result
                    : objectMapper.writeValueAsString(result);
            redis.opsForValue().set(KEY_PREFIX + hash, resultJson, TTL);
        } catch (Exception ignored) {
        }
    }

    private String hashCall(String toolName, Map<String, Object> input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(toolName.getBytes(StandardCharsets.UTF_8));
            // Sort keys for deterministic hashing
            Map<String, Object> sorted = new TreeMap<>(input == null ? Map.of() : input);
            String canonical = objectMapper.writeValueAsString(sorted);
            md.update(canonical.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(md.digest());
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            return null;
        }
    }
}
