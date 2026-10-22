package com.whaleal.mongodblog.analysis.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticTextRedactorTest {
    @Test
    void redactsQuotedJsonValuesIncludingSpacesAndEscapedQuotes() {
        String source = "{\"token\":\"json-token\",\"username\":\"json-user\","
                + "\"password\":\"escaped \\\"secret\\\" value\"}";

        assertThat(DiagnosticTextRedactor.redact(source))
                .isEqualTo("{\"token\":[REDACTED],\"username\":[REDACTED],\"password\":[REDACTED]}");
    }

    @Test
    void redactsBareAndSingleQuotedValuesAndQuotedAuthenticationUsers() {
        String source = "user=alice token=bare-token; password='a secret with spaces'; "
                + "Failed to authenticate user \"quoted-user\"; principal 'quoted-principal'";

        assertThat(DiagnosticTextRedactor.redact(source))
                .isEqualTo("user=[REDACTED] token=[REDACTED]; password=[REDACTED]; "
                        + "Failed to authenticate user [REDACTED]; principal [REDACTED]");
    }

    @Test
    void redactsIpv4Ipv6MongoDbUrisAndEmailAddresses() {
        String source = "from 192.0.2.15 and [2001:db8::2]:27017 "
                + "mongodb+srv://alice:secret@example.com/db contact=owner@example.com";

        assertThat(DiagnosticTextRedactor.redact(source))
                .contains("[IP]", "[MongoDB URI]", "[EMAIL]")
                .doesNotContain("192.0.2.15", "2001:db8::2", "alice", "secret", "owner@example.com");
    }

    @Test
    void handlesLongQuotedValuesWithoutRecursiveRegexOverflowAndKeepsTokenSuffixCoverage() {
        assertThat(DiagnosticTextRedactor.redact("password=\"" + "x".repeat(20_000) + "\""))
                .isEqualTo("password=[REDACTED]");
        assertThat(DiagnosticTextRedactor.redact("access_token=private-token; client_secret=private-secret"))
                .isEqualTo("access_token=[REDACTED]; client_secret=[REDACTED]");
    }

    @Test
    void preservesOrdinaryMessagesAndIsSafeToApplyAgainWhenExportingStoredDiagnostics() {
        String ordinary = "Client has attempted to reauthenticate as a single user";
        assertThat(DiagnosticTextRedactor.redact(ordinary)).isEqualTo(ordinary);
        for (String source : List.of("token=secret; user=alice", "{\"password\":\"a b\"}",
                "principal 'alice' from 2001:db8::2", "mongodb://alice:secret@example.com/db")) {
            String redacted = DiagnosticTextRedactor.redact(source);
            assertThat(DiagnosticTextRedactor.redact(redacted)).isEqualTo(redacted);
        }
        assertThat(DiagnosticTextRedactor.redact(null)).isEmpty();
        assertThat(DiagnosticTextRedactor.redact("")).isEmpty();
    }
}
