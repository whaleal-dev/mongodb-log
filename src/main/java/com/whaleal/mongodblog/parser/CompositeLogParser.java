package com.whaleal.mongodblog.parser;

public final class CompositeLogParser implements LogParser {
    private final LogParser structuredParser;
    private final LogParser legacyParser;

    public CompositeLogParser(LogParser structuredParser, LogParser legacyParser) {
        this.structuredParser = structuredParser;
        this.legacyParser = legacyParser;
    }

    @Override
    public ParseOutcome parse(String line, long lineNumber, int fileIndex) {
        if (line == null) {
            return ParseOutcome.skipped("EMPTY_LINE", "空行");
        }
        String normalized = line.startsWith("\uFEFF") ? line.substring(1).trim() : line.trim();
        if (normalized.startsWith("{")) {
            return structuredParser.parse(line, lineNumber, fileIndex);
        }
        return legacyParser.parse(line, lineNumber, fileIndex);
    }
}
