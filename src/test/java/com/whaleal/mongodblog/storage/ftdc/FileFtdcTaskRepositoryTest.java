package com.whaleal.mongodblog.storage.ftdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whaleal.mongodblog.task.ftdc.FtdcTask;
import com.whaleal.mongodblog.task.ftdc.FtdcTaskInputFile;
import com.whaleal.mongodblog.task.ftdc.FtdcTaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileFtdcTaskRepositoryTest {
    @TempDir
    Path directory;

    @Test
    void persistsRecoversAndDeletesOnlyTargetFtdcTask() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileFtdcTaskRepository repository = new FileFtdcTaskRepository(directory, mapper);
        FtdcTask running = task("one", FtdcTaskStatus.RUNNING);
        repository.saveTask(running);

        FileFtdcTaskRepository restarted = new FileFtdcTaskRepository(directory, mapper);
        FtdcTask recovered = restarted.findTask("one").orElseThrow();
        assertThat(recovered.status()).isEqualTo(FtdcTaskStatus.FAILED);
        assertThat(recovered.errorMessage()).contains("退出");

        Path other = Files.createDirectories(restarted.taskDirectory("other"));
        Files.writeString(other.resolve("keep"), "yes");
        restarted.deleteTask("one");
        assertThat(restarted.findTask("one")).isEmpty();
        assertThat(other.resolve("keep")).exists();
    }

    @Test
    void refusesToDeleteActiveTask() {
        FileFtdcTaskRepository repository = new FileFtdcTaskRepository(directory, new ObjectMapper().findAndRegisterModules());
        repository.saveTask(task("active", FtdcTaskStatus.QUEUED));
        assertThatThrownBy(() -> repository.deleteTask("active"))
                .isInstanceOf(com.whaleal.mongodblog.task.TaskActiveException.class);
    }

    private FtdcTask task(String id, FtdcTaskStatus status) {
        return new FtdcTask(id, id, status, 1, status == FtdcTaskStatus.QUEUED ? null : 2L, null,
                List.of(new FtdcTaskInputFile("metrics", "0000-metrics", 10)),
                10, 0, 0, 0, 0, null, null, null);
    }
}
