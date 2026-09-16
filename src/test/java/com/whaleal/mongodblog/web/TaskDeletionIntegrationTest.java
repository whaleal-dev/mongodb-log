package com.whaleal.mongodblog.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whaleal.mongodblog.analysis.AnalysisAccumulator;
import com.whaleal.mongodblog.analysis.SlowQueryRecord;
import com.whaleal.mongodblog.parser.CompositeLogParser;
import com.whaleal.mongodblog.parser.LegacyLogParser;
import com.whaleal.mongodblog.parser.QueryPatternNormalizer;
import com.whaleal.mongodblog.parser.StructuredLogParser;
import com.whaleal.mongodblog.report.MarkdownReportService;
import com.whaleal.mongodblog.storage.FileTaskRepository;
import com.whaleal.mongodblog.task.AnalysisTask;
import com.whaleal.mongodblog.task.TaskRunner;
import com.whaleal.mongodblog.task.TaskService;
import com.whaleal.mongodblog.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskDeletionIntegrationTest {
    @TempDir
    Path dataDir;
    FileTaskRepository repository;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = new FileTaskRepository(dataDir, new ObjectMapper());
        QueryPatternNormalizer normalizer = new QueryPatternNormalizer();
        CompositeLogParser parser = new CompositeLogParser(new StructuredLogParser(normalizer), new LegacyLogParser(normalizer));
        TaskRunner runner = new TaskRunner(dataDir, repository, parser);
        TaskService service = new TaskService(dataDir.toString(), repository, runner, Runnable::run);
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskController(service, repository, parser,
                        new MarkdownReportService(repository)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void deletesTerminalTasksAndAllTheirStoredFilesWithoutTouchingOtherTasksOrSource() throws Exception {
        seed("completed", TaskStatus.COMPLETED);
        seed("failed", TaskStatus.FAILED);
        seed("other", TaskStatus.COMPLETED);
        Path source = Files.writeString(dataDir.resolve("user-original.log"), "original");
        byte[] otherMetadata = Files.readAllBytes(dataDir.resolve("tasks/other/metadata.json"));
        for (String id : List.of("completed", "failed")) {
            Path work = Files.createDirectories(dataDir.resolve("work").resolve(id).resolve("nested"));
            Files.writeString(work.resolve("upload.log"), "copy");
            Files.writeString(dataDir.resolve("tasks").resolve(id).resolve("sample-extra.json"), "sample");

            mockMvc.perform(delete("/api/tasks/{id}", id)).andExpect(status().isNoContent());

            assertThat(dataDir.resolve("tasks").resolve(id)).doesNotExist();
            assertThat(dataDir.resolve("work").resolve(id)).doesNotExist();
            mockMvc.perform(get("/api/tasks/{id}", id)).andExpect(status().isNotFound());
        }
        assertThat(Files.readString(source)).isEqualTo("original");
        assertThat(Files.readAllBytes(dataDir.resolve("tasks/other/metadata.json"))).isEqualTo(otherMetadata);
        assertThat(repository.readSlowQueries("other")).hasSize(1);
        FileTaskRepository restarted = new FileTaskRepository(dataDir, new ObjectMapper());
        assertThat(restarted.listTasks()).extracting(AnalysisTask::id).containsExactly("other");
        mockMvc.perform(delete("/api/tasks/completed"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsQueuedAndRunningTasksWithoutChangingFiles() throws Exception {
        for (TaskStatus status : List.of(TaskStatus.QUEUED, TaskStatus.RUNNING)) {
            String id = status.name().toLowerCase();
            seed(id, status);
            Path work = Files.createDirectories(dataDir.resolve("work").resolve(id));
            Files.writeString(work.resolve("upload.log"), "still processing");
            mockMvc.perform(delete("/api/tasks/{id}", id))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_ACTIVE"));
            assertThat(repository.findTask(id).orElseThrow().status()).isEqualTo(status);
            assertThat(dataDir.resolve("tasks").resolve(id).resolve("summary.json")).exists();
            assertThat(work.resolve("upload.log")).hasContent("still processing");
        }
    }

    @Test
    void rejectsInvalidIdentifiersAndReportsMissingTasks() throws Exception {
        Path sentinel = Files.writeString(dataDir.resolve("outside.log"), "keep");
        for (String id : List.of("..", "bad.name", "bad\\name")) {
            mockMvc.perform(delete("/api/tasks/{id}", id))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mockMvc.perform(delete("/api/tasks/missing"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(sentinel).hasContent("keep");
    }

    @Test
    void rejectsSymlinksInsideTaskBeforeRemovingAnyData() throws Exception {
        seed("target", TaskStatus.COMPLETED);
        seed("other", TaskStatus.COMPLETED);
        Path link = dataDir.resolve("tasks/target/cross-task");
        Files.createSymbolicLink(link, dataDir.resolve("tasks/other"));
        mockMvc.perform(delete("/api/tasks/target"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("TASK_DELETE_FAILED"));
        assertThat(repository.findTask("target")).isPresent();
        assertThat(dataDir.resolve("tasks/target/summary.json")).exists();
        assertThat(repository.readSlowQueries("other")).hasSize(1);
        assertThat(Files.isSymbolicLink(link)).isTrue();
    }

    @Test
    void rejectsSymlinkedWorkDirectoryBeforeRemovingTaskData() throws Exception {
        seed("target", TaskStatus.FAILED);
        seed("other", TaskStatus.COMPLETED);
        Files.createSymbolicLink(dataDir.resolve("work/target"), dataDir.resolve("tasks/other"));
        mockMvc.perform(delete("/api/tasks/target"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("TASK_DELETE_FAILED"));
        assertThat(dataDir.resolve("tasks/target/metadata.json")).exists();
        assertThat(dataDir.resolve("tasks/other/metadata.json")).exists();
        assertThat(repository.findTask("target")).isPresent();
    }

    @Test
    void rejectsSymlinkedStorageRootWithoutFollowingIt() throws Exception {
        seed("target", TaskStatus.COMPLETED);
        Path outside = Files.createDirectories(dataDir.resolve("other-storage"));
        Path sentinel = Files.writeString(outside.resolve("do-not-touch.log"), "keep");
        Files.move(dataDir.resolve("tasks"), dataDir.resolve("original-tasks"));
        Files.createSymbolicLink(dataDir.resolve("tasks"), outside);
        mockMvc.perform(delete("/api/tasks/target"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("TASK_DELETE_FAILED"));
        assertThat(sentinel).hasContent("keep");
        assertThat(dataDir.resolve("original-tasks/target/metadata.json")).exists();
        assertThat(repository.findTask("target")).isPresent();
    }

    @Test
    void rejectsInvalidIndexTemporaryPathAndKeepsTaskDiscoverableForRetry() throws Exception {
        seed("target", TaskStatus.COMPLETED);
        Files.createDirectory(dataDir.resolve("tasks-index.json.tmp"));
        mockMvc.perform(delete("/api/tasks/target"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("TASK_DELETE_FAILED"));
        assertThat(repository.findTask("target")).isPresent();
        FileTaskRepository restarted = new FileTaskRepository(dataDir, new ObjectMapper());
        assertThat(restarted.findTask("target")).isPresent();
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void reportsCleanupPermissionFailureAndCanRetryAfterPermissionsAreFixed() throws Exception {
        seed("target", TaskStatus.COMPLETED);
        Path taskDirectory = dataDir.resolve("tasks/target");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(taskDirectory);
        try {
            Files.setPosixFilePermissions(taskDirectory, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            mockMvc.perform(delete("/api/tasks/target"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("TASK_DELETE_FAILED"));
            assertThat(repository.findTask("target")).isPresent();
            assertThat(taskDirectory.resolve("summary.json")).exists();
        } finally {
            Files.setPosixFilePermissions(taskDirectory, permissions);
        }
        mockMvc.perform(delete("/api/tasks/target")).andExpect(status().isNoContent());
        assertThat(new FileTaskRepository(dataDir, new ObjectMapper()).findTask("target")).isEmpty();
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void reportsIndexWriteFailureAfterCleanupAndRetriesWithoutResurrectingTask() throws Exception {
        seed("target", TaskStatus.COMPLETED);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(dataDir);
        try {
            Files.setPosixFilePermissions(dataDir, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            mockMvc.perform(delete("/api/tasks/target"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("TASK_DELETE_FAILED"));
            assertThat(repository.findTask("target")).isPresent();
            assertThat(dataDir.resolve("tasks/target")).doesNotExist();
            assertThat(new FileTaskRepository(dataDir, new ObjectMapper()).findTask("target")).isPresent();
        } finally {
            Files.setPosixFilePermissions(dataDir, permissions);
        }
        mockMvc.perform(delete("/api/tasks/target")).andExpect(status().isNoContent());
        assertThat(new FileTaskRepository(dataDir, new ObjectMapper()).findTask("target")).isEmpty();
    }

    private void seed(String id, TaskStatus status) {
        repository.saveTask(new AnalysisTask(id, id, status, 1_000, null, null,
                List.of(), 0, 0, 0, null, null, null));
        repository.saveResult(id, new AnalysisAccumulator(1).finish(), List.of(new SlowQueryRecord(
                "0-1", 0, 1, 1_000, "find", "db.a", 100, null, 1L, "IXSCAN", "ip", "{}", "raw", Map.of())));
    }
}
