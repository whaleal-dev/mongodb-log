package com.whaleal.mongodblog.parser;

import java.util.Optional;

public record ParseOutcome(
        ParseStatus status,
        Optional<ParsedLogEntry> entry,
        String errorCode,
        String errorMessage
) {
    public static ParseOutcome success(ParsedLogEntry entry) {
        return new ParseOutcome(ParseStatus.SUCCESS, Optional.of(entry), null, null);
    }

    public static ParseOutcome partial(ParsedLogEntry entry, String errorCode, String errorMessage) {
        return new ParseOutcome(ParseStatus.PARTIAL, Optional.of(entry), errorCode, errorMessage);
    }

    public static ParseOutcome skipped(String errorCode, String errorMessage) {
        return new ParseOutcome(ParseStatus.SKIPPED, Optional.empty(), errorCode, errorMessage);
    }

    public static ParseOutcome failed(String errorCode, String errorMessage) {
        return new ParseOutcome(ParseStatus.FAILED, Optional.empty(), errorCode, errorMessage);
    }
}

