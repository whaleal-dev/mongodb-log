package com.whaleal.mongodblog.task;

import com.whaleal.mongodblog.storage.TaskRepository;
import com.whaleal.mongodblog.storage.ftdc.FtdcTaskRepository;
import com.whaleal.mongodblog.task.ftdc.FtdcTask;
import com.whaleal.mongodblog.task.ftdc.FtdcTaskStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ApplicationMaintenanceService {
    private final TaskRepository logTasks;
    private final FtdcTaskRepository metricTasks;

    public ApplicationMaintenanceService(TaskRepository logTasks, FtdcTaskRepository metricTasks) {
        this.logTasks = logTasks;
        this.metricTasks = metricTasks;
    }

    public MemoryUsage memoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long used = Math.max(0, runtime.totalMemory() - runtime.freeMemory());
        int percent = max > 0 ? (int) Math.min(100, Math.round(used * 100.0 / max)) : 0;
        return new MemoryUsage(used, max, percent);
    }

    public synchronized void clearAllData() {
        List<AnalysisTask> logs = logTasks.listTasks();
        List<FtdcTask> metrics = metricTasks.listTasks();
        boolean active = logs.stream().anyMatch(task -> !terminal(task.status()))
                || metrics.stream().anyMatch(task -> !terminal(task.status()));
        if (active) {
            throw new TaskActiveException("存在排队中或运行中的任务，任务结束后才能清空全部数据");
        }
        logs.forEach(task -> logTasks.deleteTask(task.id()));
        metrics.forEach(task -> metricTasks.deleteTask(task.id()));
    }

    private boolean terminal(TaskStatus status) {
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED;
    }

    private boolean terminal(FtdcTaskStatus status) {
        return status == FtdcTaskStatus.COMPLETED || status == FtdcTaskStatus.FAILED;
    }

    public record MemoryUsage(long usedBytes, long maxBytes, int usagePercent) {
    }
}
