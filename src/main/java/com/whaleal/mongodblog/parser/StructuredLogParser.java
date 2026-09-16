package com.whaleal.mongodblog.parser;

import com.whaleal.mongodblog.analysis.diagnostics.LogEnvelopeMetadata;
import org.bson.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

public final class StructuredLogParser implements LogParser {
    private static final List<String> COMMAND_OPERATIONS = List.of(
            "find", "aggregate", "insert", "update", "delete", "getMore",
            "findAndModify", "createIndexes", "addShard", "replSetInitiate", "createUser", "moveChunk"
    );

    private final QueryPatternNormalizer patternNormalizer;

    public StructuredLogParser(QueryPatternNormalizer patternNormalizer) {
        this.patternNormalizer = patternNormalizer;
    }

    @Override
    public ParseOutcome parse(String line, long lineNumber, int fileIndex) {
        if (line == null || stripBom(line).trim().isEmpty()) {
            return ParseOutcome.skipped("EMPTY_LINE", "空行");
        }
        String normalizedLine = stripBom(line).trim();
        if (!normalizedLine.startsWith("{")) {
            return ParseOutcome.skipped("NOT_STRUCTURED", "不是结构化 JSON 日志");
        }

        Document root;
        try {
            root = Document.parse(normalizedLine);
        } catch (RuntimeException e) {
            return ParseOutcome.failed("INVALID_STRUCTURED_JSON", conciseMessage(e));
        }

        Long timestamp = timestamp(root.get("t"));
        if (timestamp == null) {
            return ParseOutcome.failed("MISSING_TIMESTAMP", "结构化日志缺少有效的 t.$date");
        }

        Document attr = asDocument(root.get("attr"));
        Document command = attr == null ? null : asDocument(attr.get("command"));
        Document originatingCommand = attr == null ? null : asDocument(attr.get("originatingCommand"));
        String message = stringValue(root.get("msg"));
        Long duration = numberValue(attr, "durationMillis");
        boolean slowQuery = "Slow query".equalsIgnoreCase(message);
        String operation = operation(command, attr);

        try {
            ParsedLogEntry entry = new ParsedLogEntry(
                    lineNumber,
                    fileIndex,
                    timestamp,
                    stringValue(root.get("s")),
                    stringValue(root.get("c")),
                    integerValue(root.get("id")),
                    stringValue(root.get("ctx")),
                    message,
                    attr == null ? null : stringValue(attr.get("ns")),
                    operation,
                    duration,
                    numberValue(attr, "cpuNanos"),
                    numberValue(attr, "reslen"),
                    attr == null ? null : stringValue(attr.get("planSummary")),
                    remote(attr),
                    patternNormalizer.normalize(patternSource(operation, command, originatingCommand)),
                    line,
                    attr == null ? Map.of() : attr,
                    slowQuery,
                    message != null && message.contains("Heartbeat failed"),
                    envelopeMetadata(root)
            );
            if (slowQuery && duration == null) {
                boolean missing = attr == null || attr.get("durationMillis") == null;
                return ParseOutcome.partial(entry, missing ? "MISSING_SLOW_QUERY_DURATION" : "INVALID_SLOW_QUERY_DURATION",
                        "慢查询缺少有效的非负整数耗时，未纳入耗时统计");
            }
            if (slowQuery && (invalidNumber(attr, "cpuNanos") || invalidNumber(attr, "reslen"))) {
                return ParseOutcome.partial(entry, "INVALID_SLOW_QUERY_METRICS", "CPU 或响应大小不是有效的非负整数，已排除异常指标");
            }
            return ParseOutcome.success(entry);
        } catch (RuntimeException e) {
            ParsedLogEntry entry = new ParsedLogEntry(
                    lineNumber, fileIndex, timestamp, stringValue(root.get("s")), stringValue(root.get("c")),
                    integerValue(root.get("id")), stringValue(root.get("ctx")), message,
                    attr == null ? null : stringValue(attr.get("ns")), operation, duration,
                    numberValue(attr, "cpuNanos"), numberValue(attr, "reslen"),
                    attr == null ? null : stringValue(attr.get("planSummary")), remote(attr), "{}", line,
                    attr == null ? Map.of() : attr, slowQuery,
                    message != null && message.contains("Heartbeat failed"), envelopeMetadata(root)
            );
            return ParseOutcome.partial(entry, "PATTERN_NORMALIZATION_FAILED", conciseMessage(e));
        }
    }

    private Object patternSource(String operation, Document command, Document originatingCommand) {
        if (command == null) {
            return null;
        }
        return switch (operation) {
            case "find" -> command.get("filter");
            case "aggregate" -> command.get("pipeline");
            case "update" -> command.get("updates") instanceof List<?> updates
                    ? updates.stream().map(this::asDocument).map(statement -> statement == null ? null : statement.get("q")).toList()
                    : command.get("q");
            case "delete" -> command.get("deletes");
            case "findAndModify" -> command.get("query");
            case "getMore" -> originatingCommand == null ? null : originatingCommand.containsKey("pipeline")
                    ? originatingCommand.get("pipeline") : originatingCommand.get("filter");
            case "createIndexes" -> command.get("indexes");
            default -> command.get("q");
        };
    }

    private LogEnvelopeMetadata envelopeMetadata(Document root) {
        List<String> tags = root.get("tags") instanceof List<?> values
                ? values.stream().map(String::valueOf).toList()
                : List.of();
        return new LogEnvelopeMetadata(stringValue(root.get("svc")), tags, root.get("truncated") != null);
    }

    private String operation(Document command, Document attr) {
        if (command != null && !command.isEmpty()) {
            String commandName = command.keySet().iterator().next();
            if (COMMAND_OPERATIONS.contains(commandName)) {
                return commandName;
            }
        }
        String type = attr == null ? null : stringValue(attr.get("type"));
        return type == null || type.isBlank() ? "command" : type;
    }

    private String remote(Document attr) {
        if (attr == null) {
            return null;
        }
        String remote = stringValue(attr.get("remote"));
        return remoteAddress(remote == null ? stringValue(attr.get("client")) : remote);
    }

    private String remoteAddress(String remote) {
        if (remote == null || remote.isBlank()) {
            return null;
        }
        if (remote.startsWith("[")) {
            int end = remote.indexOf(']');
            return end > 1 ? remote.substring(1, end) : remote;
        }
        int firstColon = remote.indexOf(':');
        int lastColon = remote.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon && remote.substring(lastColon + 1).matches("\\d+")) {
            return remote.substring(0, lastColon);
        }
        return remote;
    }

    private Long timestamp(Object value) {
        if (value instanceof Date date) {
            return date.getTime();
        }
        if (value instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        if (value instanceof Document document) {
            return timestamp(document.get("$date"));
        }
        if (value instanceof Map<?, ?> map) {
            return timestamp(map.get("$date"));
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Instant.parse(text).toEpochMilli();
            } catch (RuntimeException ignored) {
                try {
                    return OffsetDateTime.parse(text).toInstant().toEpochMilli();
                } catch (RuntimeException invalidTimestamp) {
                    return null;
                }
            }
        }
        return null;
    }

    private Document asDocument(Object value) {
        if (value instanceof Document document) {
            return document;
        }
        if (value instanceof Map<?, ?> map) {
            Document document = new Document();
            map.forEach((key, item) -> document.put(String.valueOf(key), item));
            return document;
        }
        return null;
    }

    private Long numberValue(Document source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        try {
            Long parsed = null;
            if (value instanceof Number number) {
                parsed = new BigDecimal(number.toString()).longValueExact();
            } else if (value instanceof String text) {
                parsed = Long.parseLong(text);
            }
            return parsed != null && parsed >= 0 ? parsed : null;
        } catch (NumberFormatException | ArithmeticException invalid) {
            return null;
        }
    }

    private boolean invalidNumber(Document source, String key) {
        return source != null && source.get(key) != null && numberValue(source, key) == null;
    }

    private Integer integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String stripBom(String line) {
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }

    private String conciseMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
