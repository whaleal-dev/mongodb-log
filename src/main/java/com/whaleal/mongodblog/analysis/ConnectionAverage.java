package com.whaleal.mongodblog.analysis;

public record ConnectionAverage(long timestampEpochMillis, long sampleCount, double averageConnections) {
}
