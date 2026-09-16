package com.whaleal.mongodblog.parser;

import com.whaleal.mongodblog.analysis.diagnostics.LogEnvelopeMetadata;

import java.util.Map;

public record ParsedLogEntry(
        long lineNumber,
        int fileIndex,
        long timestampEpochMillis,
        String severity,
        String component,
        Integer messageId,
        String context,
        String message,
        String namespace,
        String operation,
        Long durationMillis,
        Long cpuNanos,
        Long responseLength,
        String planSummary,
        String remote,
        String queryPattern,
        String rawLine,
        Map<String, Object> attributes,
        boolean slowQuery,
        boolean heartbeatFailure,
        LogEnvelopeMetadata envelopeMetadata
) {
    public ParsedLogEntry(
            long lineNumber,
            int fileIndex,
            long timestampEpochMillis,
            String severity,
            String component,
            Integer messageId,
            String context,
            String message,
            String namespace,
            String operation,
            Long durationMillis,
            Long cpuNanos,
            Long responseLength,
            String planSummary,
            String remote,
            String queryPattern,
            String rawLine,
            Map<String, Object> attributes,
            boolean slowQuery,
            boolean heartbeatFailure
    ) {
        this(lineNumber, fileIndex, timestampEpochMillis, severity, component, messageId, context, message,
                namespace, operation, durationMillis, cpuNanos, responseLength, planSummary, remote, queryPattern,
                rawLine, attributes, slowQuery, heartbeatFailure, null);
    }
}
