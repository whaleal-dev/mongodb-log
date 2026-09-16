package com.whaleal.mongodblog.analysis.diagnostics;

import java.util.List;

public record LogEnvelopeMetadata(
        String service,
        List<String> tags,
        boolean truncated
) {
    public LogEnvelopeMetadata {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
