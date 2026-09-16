package com.whaleal.mongodblog.task.ftdc;

import java.util.List;

public record FtdcTask(
        String id,
        String name,
        FtdcTaskStatus status,
        long createdAtEpochMillis,
        Long startedAtEpochMillis,
        Long completedAtEpochMillis,
        List<FtdcTaskInputFile> files,
        long totalBytes,
        long processedBytes,
        int blockCount,
        int metricCount,
        long sampleCount,
        Long startEpochMillis,
        Long endEpochMillis,
        String errorMessage
) {
    public FtdcTask {
        files = List.copyOf(files);
    }

    public double progressPercentage() {
        if (status == FtdcTaskStatus.COMPLETED) return 100;
        return totalBytes == 0 ? 0 : Math.min(99.9, processedBytes * 100.0 / totalBytes);
    }

    public FtdcTask running(long now) {
        return new FtdcTask(id, name, FtdcTaskStatus.RUNNING, createdAtEpochMillis, now, null, files,
                totalBytes, processedBytes, blockCount, metricCount, sampleCount, startEpochMillis, endEpochMillis, null);
    }

    public FtdcTask progress(long bytes, int blocks) {
        return new FtdcTask(id, name, status, createdAtEpochMillis, startedAtEpochMillis, completedAtEpochMillis,
                files, totalBytes, bytes, blocks, metricCount, sampleCount, startEpochMillis, endEpochMillis, errorMessage);
    }

    public FtdcTask completed(long now, int blocks, int metrics, long samples, Long start, Long end) {
        return new FtdcTask(id, name, FtdcTaskStatus.COMPLETED, createdAtEpochMillis, startedAtEpochMillis, now,
                files, totalBytes, totalBytes, blocks, metrics, samples, start, end, null);
    }

    public FtdcTask failed(long now, String error) {
        return new FtdcTask(id, name, FtdcTaskStatus.FAILED, createdAtEpochMillis, startedAtEpochMillis, now,
                files, totalBytes, processedBytes, blockCount, metricCount, sampleCount,
                startEpochMillis, endEpochMillis, error);
    }
}
