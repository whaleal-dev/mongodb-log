package com.whaleal.mongodblog.storage;

import com.whaleal.mongodblog.analysis.AnalysisSummary;
import com.whaleal.mongodblog.analysis.SlowQueryRecord;
import com.whaleal.mongodblog.analysis.diagnostics.LogDiagnostics;
import com.whaleal.mongodblog.task.AnalysisTask;

import java.util.List;
import java.util.Optional;

public interface TaskRepository {
    void saveTask(AnalysisTask task);

    void deleteTask(String id);

    Optional<AnalysisTask> findTask(String id);

    List<AnalysisTask> listTasks();

    void saveResult(String taskId, AnalysisSummary summary, List<SlowQueryRecord> slowQueries);

    void saveDiagnostics(String taskId, LogDiagnostics diagnostics);

    AnalysisSummary readSummary(String taskId);

    Optional<LogDiagnostics> readDiagnostics(String taskId);

    List<SlowQueryRecord> readSlowQueries(String taskId);

    Optional<SlowQueryRecord> readSlowQuery(String taskId, String queryId);
}
