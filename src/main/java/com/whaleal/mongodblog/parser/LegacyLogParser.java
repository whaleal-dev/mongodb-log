package com.whaleal.mongodblog.parser;

import org.bson.Document;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LegacyLogParser implements LogParser {
    private static final Pattern HEADER = Pattern.compile("^(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+\\[([^]]+)]\\s+(.*)$");
    private static final Pattern LEGACY_TIMESTAMP = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T");
    private static final Pattern COMMAND_OPERATION = Pattern.compile("command:\\s*(findAndModify|createIndexes|aggregate|insert|getMore|find)\\s*\\{");
    private static final Pattern DURATION = Pattern.compile("(?:^|\\s)([^\\s]+)ms\\s*$");
    private static final Pattern PLAN = Pattern.compile("planSummary:\\s*(\\S+)");
    private static final Pattern RESPONSE_LENGTH = Pattern.compile("reslen:\\s*(\\S+)");
    private static final Pattern NETWORK_REMOTE = Pattern.compile("(?:connection accepted from|end connection)\\s+([^ :]+)(?::\\d+)?");
    private static final Pattern LEGACY_KEY = Pattern.compile("([,{\\[]\\s*)([$A-Za-z0-9_.]+)(\\s*:)");
    private static final Pattern BSON_CONSTRUCTOR = Pattern.compile("(?:new\\s+)?(?:Timestamp|BinData|UUID|ObjectId|NumberLong|NumberInt|ISODate|Date)\\s*\\(");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX");

    private final QueryPatternNormalizer patternNormalizer;

    public LegacyLogParser(QueryPatternNormalizer patternNormalizer) {
        this.patternNormalizer = patternNormalizer;
    }

    @Override
    public ParseOutcome parse(String line, long lineNumber, int fileIndex) {
        if (line == null || stripBom(line).trim().isEmpty()) {
            return ParseOutcome.skipped("EMPTY_LINE", "空行");
        }
        String normalized = stripBom(line).trim();
        Matcher header = HEADER.matcher(normalized);
        if (!header.matches()) {
            if (LEGACY_TIMESTAMP.matcher(normalized).find()) {
                return ParseOutcome.failed("INVALID_LEGACY_HEADER", "旧版日志头格式不完整");
            }
            return ParseOutcome.skipped("NOT_MONGODB_LOG", "不是可识别的 MongoDB 日志");
        }

        Long timestamp = timestamp(header.group(1));
        if (timestamp == null) {
            return ParseOutcome.failed("INVALID_TIMESTAMP", "旧版日志时间无效");
        }
        String component = header.group(3);
        String message = header.group(5);
        String operation = operation(component, message);
        String namespace = namespace(message);
        Long duration = firstLong(DURATION, message);
        String planSummary = firstValue(PLAN, message);
        Long responseLength = firstLong(RESPONSE_LENGTH, message);
        String remote = networkRemote(message);
        boolean slowQuery = isSlowOperation(operation);
        boolean heartbeatFailure = message.contains("Heartbeat failed");

        Document command = commandDocument(operation, message).orElse(null);
        boolean patternExpected = slowQuery && !"insert".equals(operation);
        String queryPattern = "{}";
        if (command != null) {
            Object source = patternSource(operation, command, message);
            queryPattern = patternNormalizer.normalize(source);
            if ("createIndexes".equals(operation)) {
                String database = stringValue(command.get("$db"));
                String collection = stringValue(command.get("createIndexes"));
                if (database != null && collection != null) {
                    namespace = database + "." + collection;
                }
            }
        }

        ParsedLogEntry entry = new ParsedLogEntry(
                lineNumber,
                fileIndex,
                timestamp,
                header.group(2),
                component,
                null,
                header.group(4),
                message,
                namespace,
                operation,
                duration,
                null,
                responseLength,
                planSummary,
                remote,
                queryPattern,
                line,
                command == null ? Map.of() : command,
                slowQuery,
                heartbeatFailure
        );
        if (slowQuery && duration == null) {
            return ParseOutcome.partial(entry, firstValue(DURATION, message) == null
                            ? "MISSING_SLOW_QUERY_DURATION" : "INVALID_SLOW_QUERY_DURATION",
                    "慢查询缺少有效的非负整数耗时，未纳入耗时统计");
        }
        if (slowQuery && firstValue(RESPONSE_LENGTH, message) != null && responseLength == null) {
            return ParseOutcome.partial(entry, "INVALID_SLOW_QUERY_METRICS", "响应大小不是有效的非负整数，已排除异常指标");
        }
        if (patternExpected && command == null) {
            return ParseOutcome.partial(entry, "LEGACY_COMMAND_PARTIAL", "命令主体不是完整的可解析 BSON 文档");
        }
        return ParseOutcome.success(entry);
    }

    private Optional<Document> commandDocument(String operation, String message) {
        if (operation == null || "network".equals(operation)) {
            return Optional.empty();
        }
        String marker = switch (operation) {
            case "update", "remove" -> "command:";
            default -> "command: " + operation;
        };
        return BalancedDocumentExtractor.extractAfter(message, marker).flatMap(this::parseLegacyDocument);
    }

    private Object patternSource(String operation, Document command, String message) {
        return switch (operation) {
            case "find" -> command.get("filter");
            case "aggregate" -> command.get("pipeline");
            case "update", "remove" -> command.get("q");
            case "findAndModify" -> command.get("query");
            case "getMore" -> BalancedDocumentExtractor.extractAfter(message, "originatingCommand:")
                    .flatMap(this::parseLegacyDocument)
                    .map(document -> document.containsKey("pipeline") ? document.get("pipeline") : document.get("filter"))
                    .orElse(null);
            case "createIndexes" -> command.get("indexes");
            default -> null;
        };
    }

    private Optional<Document> parseLegacyDocument(String document) {
        String sanitized = replaceBsonConstructors(document);
        Matcher matcher = LEGACY_KEY.matcher(sanitized);
        StringBuilder json = new StringBuilder();
        while (matcher.find()) {
            String replacement = matcher.group(1) + "\"" + matcher.group(2) + "\"" + matcher.group(3);
            matcher.appendReplacement(json, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(json);
        try {
            return Optional.of(Document.parse(json.toString()));
        } catch (RuntimeException invalidBson) {
            return Optional.empty();
        }
    }

    private String replaceBsonConstructors(String source) {
        String current = source;
        Matcher matcher = BSON_CONSTRUCTOR.matcher(current);
        while (matcher.find()) {
            int openParenthesis = current.indexOf('(', matcher.start());
            int end = matchingParenthesis(current, openParenthesis);
            if (end < 0) {
                return current;
            }
            current = current.substring(0, matcher.start()) + "\"?\"" + current.substring(end + 1);
            matcher = BSON_CONSTRUCTOR.matcher(current);
        }
        return current;
    }

    private int matchingParenthesis(String source, int start) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < source.length(); index++) {
            char current = source.charAt(index);
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
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private String operation(String component, String message) {
        if ("WRITE".equalsIgnoreCase(component)) {
            if (message.startsWith("update ")) {
                return "update";
            }
            if (message.startsWith("remove ")) {
                return "remove";
            }
        }
        if ("COMMAND".equalsIgnoreCase(component)) {
            Matcher matcher = COMMAND_OPERATION.matcher(message);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        if ("NETWORK".equalsIgnoreCase(component)) {
            return "network";
        }
        return component.toLowerCase();
    }

    private String namespace(String message) {
        String[] parts = message.split("\\s+", 3);
        if (parts.length < 2) {
            return null;
        }
        return switch (parts[0]) {
            case "command", "update", "remove", "insert" -> parts[1];
            default -> null;
        };
    }

    private boolean isSlowOperation(String operation) {
        return switch (operation) {
            case "find", "aggregate", "insert", "update", "remove", "getMore", "findAndModify", "createIndexes" -> true;
            default -> false;
        };
    }

    private String networkRemote(String message) {
        Matcher matcher = NETWORK_REMOTE.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Long timestamp(String text) {
        try {
            return OffsetDateTime.parse(text, TIMESTAMP_FORMAT).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Long firstLong(Pattern pattern, String text) {
        String value = firstValue(pattern, text);
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private String firstValue(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String stripBom(String line) {
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }
}
