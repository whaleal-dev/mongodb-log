package com.whaleal.mongodblog.parser;

import java.util.Optional;

public final class BalancedDocumentExtractor {
    private BalancedDocumentExtractor() {
    }

    public static Optional<String> extractAfter(String text, String marker) {
        if (text == null || marker == null) {
            return Optional.empty();
        }
        int markerIndex = text.indexOf(marker);
        if (markerIndex < 0) {
            return Optional.empty();
        }
        int start = text.indexOf('{', markerIndex + marker.length());
        if (start < 0) {
            return Optional.empty();
        }

        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    quoted = false;
                }
                continue;
            }
            if (current == '"') {
                quoted = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return Optional.of(text.substring(start, index + 1));
                }
            }
        }
        return Optional.empty();
    }
}
