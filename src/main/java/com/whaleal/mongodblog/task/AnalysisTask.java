package com.whaleal.mongodblog.task;

import java.util.List;

public record AnalysisTask(
        String id,
        String name,
        TaskStatus status,
        long createdAtEpochMillis,
        Long startedAtEpochMillis,
        Long completedAtEpochMillis,
        List<TaskInputFile> files,
        long totalBytes,
        long processedBytes,
        long processedLines,
        String errorMessage,
        Long logStartEpochMillis,
        Long logEndEpochMillis
) {
    public double progressPercentage() {
        if (status == TaskStatus.COMPLETED) {
            return 100;
        }
        return totalBytes == 0 ? 0 : Math.min(99.9, processedBytes * 100.0 / totalBytes);
    }

    public AnalysisTask running(long now) {
        return new AnalysisTask(id, name, TaskStatus.RUNNING, createdAtEpochMillis, now, null,
                files, totalBytes, processedBytes, processedLines, null, logStartEpochMillis, logEndEpochMillis);
    }

    public AnalysisTask progress(long bytes, long lines) {
        return new AnalysisTask(id, name, status, createdAtEpochMillis, startedAtEpochMillis,
                completedAtEpochMillis, files, totalBytes, bytes, lines, errorMessage, logStartEpochMillis, logEndEpochMillis);
    }

    public AnalysisTask completed(long now, long bytes, long lines, Long logStart, Long logEnd) {
        return new AnalysisTask(id, name, TaskStatus.COMPLETED, createdAtEpochMillis, startedAtEpochMillis,
                now, files, totalBytes, bytes, lines, null, logStart, logEnd);
    }

    public AnalysisTask failed(long now, String error) {
        return new AnalysisTask(id, name, TaskStatus.FAILED, createdAtEpochMillis, startedAtEpochMillis,
                now, files, totalBytes, processedBytes, processedLines, error, logStartEpochMillis, logEndEpochMillis);
    }
}
