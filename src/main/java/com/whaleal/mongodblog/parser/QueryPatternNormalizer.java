package com.whaleal.mongodblog.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class QueryPatternNormalizer {
    private static final String PLACEHOLDER = "?";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String normalize(Object value) {
        if (value == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(normalizeValue(value));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无法生成查询模式", e);
        }
    }

    private Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new TreeMap<>();
            map.forEach((key, item) -> normalized.put(String.valueOf(key), normalizeValue(item)));
            return normalized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            iterable.forEach(item -> normalized.add(normalizeValue(item)));
            return normalized;
        }
        return PLACEHOLDER;
    }
}

