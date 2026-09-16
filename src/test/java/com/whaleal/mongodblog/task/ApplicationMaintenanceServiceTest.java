package com.whaleal.mongodblog.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whaleal.mongodblog.storage.FileTaskRepository;
import com.whaleal.mongodblog.storage.TaskRepository;
import com.whaleal.mongodblog.storage.ftdc.FileFtdcTaskRepository;
import com.whaleal.mongodblog.storage.ftdc.FtdcTaskRepository;
import com.whaleal.mongodblog.task.ftdc.FtdcTask;
import com.whaleal.mongodblog.task.ftdc.FtdcTaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationMaintenanceServiceTest {

    @TempDir
    Path dataDirectory;

    @Test
    void reportsJvmHeapUsageAgainstTheConfiguredMaximum() {
        ApplicationMaintenanceService service = new ApplicationMaintenanceService(
                mock(TaskRepository.class), mock(FtdcTaskRepository.class));

        ApplicationMaintenanceService.MemoryUsage usage = service.memoryUsage();

        assertThat(usage.usedBytes()).isGreaterThanOrEqualTo(0);
        assertThat(usage.maxBytes()).isGreaterThan(0);
        assertThat(usage.usedBytes()).isLessThanOrEqualTo(usage.maxBytes());
        assertThat(usage.usagePercent()).isBetween(0, 100);
    }

    @Test
    void clearsAllTerminalLogAndMetricTasks() {
        TaskRepository logs = mock(TaskRepository.class);
        FtdcTaskRepository metrics = mock(FtdcTaskRepository.class);
        when(logs.listTasks()).thenReturn(List.of(logTask("log", TaskStatus.COMPLETED)));
        when(metrics.listTasks()).thenReturn(List.of(metricTask("metric", FtdcTaskStatus.FAILED)));

        new ApplicationMaintenanceService(logs, metrics).clearAllData();

        verify(logs).deleteTask("log");
        verify(metrics).deleteTask("metric");
    }

    @Test
    void removesTheStoredTaskDirectoriesAndIndexesFromAnIsolatedDataDirectory() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileTaskRepository logs = new FileTaskRepository(dataDirectory, mapper);
        FileFtdcTaskRepository metrics = new FileFtdcTaskRepository(dataDirectory, mapper);
        logs.saveTask(logTask("log", TaskStatus.COMPLETED));
        Files.createDirectories(metrics.taskDirectory("metric"));
        metrics.saveTask(metricTask("metric", FtdcTaskStatus.COMPLETED));

        new ApplicationMaintenanceService(logs, metrics).clearAllData();

        assertThat(logs.listTasks()).isEmpty();
        assertThat(metrics.listTasks()).isEmpty();
        assertThat(dataDirectory.resolve("tasks/log")).doesNotExist();
        assertThat(metrics.taskDirectory("metric")).doesNotExist();
    }

    @Test
    void refusesTheWholeClearBeforeDeletingAnythingWhenATaskIsActive() {
        TaskRepository logs = mock(TaskRepository.class);
        FtdcTaskRepository metrics = mock(FtdcTaskRepository.class);
        when(logs.listTasks()).thenReturn(List.of(logTask("log", TaskStatus.COMPLETED)));
        when(metrics.listTasks()).thenReturn(List.of(metricTask("metric", FtdcTaskStatus.RUNNING)));

        ApplicationMaintenanceService service = new ApplicationMaintenanceService(logs, metrics);

        assertThatThrownBy(service::clearAllData)
                .isInstanceOf(TaskActiveException.class)
                .hasMessageContaining("运行中");
        verify(logs, never()).deleteTask("log");
        verify(metrics, never()).deleteTask("metric");
    }

    private AnalysisTask logTask(String id, TaskStatus status) {
        return new AnalysisTask(id, id, status, 1, null, null, List.of(), 0, 0, 0,
                null, null, null);
    }

    private FtdcTask metricTask(String id, FtdcTaskStatus status) {
        return new FtdcTask(id, id, status, 1, null, null, List.of(), 0, 0, 0, 0, 0,
                null, null, null);
    }
}
