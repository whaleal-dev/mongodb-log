package com.whaleal.mongodblog.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BalancedDocumentExtractorTest {

    @Test
    void extractsNestedDocument() {
        String text = "command: { filter: { status: \"OPEN\" }, limit: 1 } planSummary: IXSCAN";

        assertThat(BalancedDocumentExtractor.extractAfter(text, "command:"))
                .contains("{ filter: { status: \"OPEN\" }, limit: 1 }");
    }

    @Test
    void ignoresBracesInsideQuotedStringsAndEscapedQuotes() {
        String text = "command: { note: \"left { brace } and \\\"quote\\\"\", ok: true } 120ms";

        assertThat(BalancedDocumentExtractor.extractAfter(text, "command:"))
                .contains("{ note: \"left { brace } and \\\"quote\\\"\", ok: true }");
    }

    @Test
    void returnsEmptyWhenClosingBraceOrMarkerIsMissing() {
        assertThat(BalancedDocumentExtractor.extractAfter("command: { a: 1", "command:")).isEmpty();
        assertThat(BalancedDocumentExtractor.extractAfter("{ a: 1 }", "command:")).isEmpty();
    }
}
