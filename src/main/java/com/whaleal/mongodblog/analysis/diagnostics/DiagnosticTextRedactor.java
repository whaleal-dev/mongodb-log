package com.whaleal.mongodblog.analysis.diagnostics;

import java.util.regex.Pattern;

public final class DiagnosticTextRedactor {
    private static final String QUOTED_VALUE = "\"(?:\\\\.|[^\"\\\\])*+\"|'(?:\\\\.|[^'\\\\])*+'";
    private static final String VALUE = "(?:\\[REDACTED\\]|" + QUOTED_VALUE + "|[^\\s,;\\]}]+)";
    private static final Pattern SECRET = Pattern.compile(
            "(?i)((?:password|passwd|token|secret)[\"']?\\s*[:=]\\s*)" + VALUE);
    private static final Pattern USER_IDENTITY = Pattern.compile(
            "(?i)(\\b(?:user(?:name)?|principal)\\b[\"']?\\s*[:=]\\s*)" + VALUE);
    private static final Pattern QUOTED_USER = Pattern.compile(
            "(?i)(\\b(?:user(?:name)?|principal)\\s+)(?:" + QUOTED_VALUE + ")");
    private static final Pattern MONGODB_URI = Pattern.compile("(?i)mongodb(?:\\+srv)?://\\S+");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern IPV6 = Pattern.compile(
            "(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.]{0,15}(?:%[a-z0-9_.-]+)?(?![0-9a-f:])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private DiagnosticTextRedactor() {
    }

    public static String redact(String value) {
        if (value == null) return "";
        String redacted = SECRET.matcher(value).replaceAll("$1[REDACTED]");
        redacted = USER_IDENTITY.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = QUOTED_USER.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = MONGODB_URI.matcher(redacted).replaceAll("[MongoDB URI]");
        redacted = IPV6.matcher(redacted).replaceAll("[IP]");
        redacted = IPV4.matcher(redacted).replaceAll("[IP]");
        return EMAIL.matcher(redacted).replaceAll("[EMAIL]");
    }
}
