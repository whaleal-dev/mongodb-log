package com.whaleal.mongodblog.storage.ftdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whaleal.mongodblog.task.ftdc.FtdcTask;
import com.whaleal.mongodblog.task.ftdc.FtdcTaskInputFile;
import com.whaleal.mongodblog.task.ftdc.FtdcTaskStatus;
import com.whaleal.mongodblog.task.TaskDeletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"data", "data/ftdc", "data/ftdc/tasks", "data/ftdc/work"})
    void rejectsSymlinkedAncestorsWithoutDeletingExternalOrOriginalFiles(String ancestorPath) throws Exception {
        FileFtdcTaskRepository repository = new FileFtdcTaskRepository(directory.resolve("data"), new ObjectMapper());
        repository.saveTask(task("target", FtdcTaskStatus.FAILED));
        Path metadata = repository.taskDirectory("target").resolve("metadata.json");
        Path workFile = Files.createDirectories(repository.workDirectory("target")).resolve("keep");
        Files.writeString(workFile, "work");
        Path ancestor = directory.resolve(ancestorPath);
        Path original = ancestor.resolveSibling(ancestor.getFileName() + "-original");
        Files.move(ancestor, original);
        Path outside = Files.createDirectories(directory.resolve("outside"));
        Path externalTask = outside.resolve(switch (ancestorPath) {
            case "data" -> "ftdc/tasks/target";
            case "data/ftdc" -> "tasks/target";
            default -> "target";
        });
        Path sentinel = Files.createDirectories(externalTask).resolve("keep");
        Files.writeString(sentinel, "external");
        Files.createSymbolicLink(ancestor, outside);

        assertThatThrownBy(() -> repository.deleteTask("target")).isInstanceOf(TaskDeletionException.class);

        assertThat(sentinel).hasContent("external");
        assertThat(metadata.startsWith(ancestor) ? original.resolve(ancestor.relativize(metadata)) : metadata).exists();
        assertThat(workFile.startsWith(ancestor) ? original.resolve(ancestor.relativize(workFile)) : workFile).hasContent("work");
        assertThat(repository.findTask("target")).isPresent();
    }

    @ParameterizedTest
    @CsvSource({"tasks-index.json,directory", "tasks-index.json.tmp,directory",
            "tasks-index.json,symlink", "tasks-index.json.tmp,symlink"})
    void rejectsInvalidIndexPathsBeforeDeletingTaskData(String fileName, String type) throws Exception {
        FileFtdcTaskRepository repository = new FileFtdcTaskRepository(directory, new ObjectMapper());
        repository.saveTask(task("target", FtdcTaskStatus.FAILED));
        Path metadata = repository.taskDirectory("target").resolve("metadata.json");
        Path workFile = Files.createDirectories(repository.workDirectory("target")).resolve("keep");
        Files.writeString(workFile, "work");
        Path invalid = directory.resolve("ftdc").resolve(fileName);
        if (Files.exists(invalid)) Files.move(invalid, invalid.resolveSibling(fileName + ".original"));
        Path external = Files.writeString(directory.resolve("outside.json"), "external");
        if (type.equals("directory")) Files.createDirectory(invalid);
        else Files.createSymbolicLink(invalid, external);

        assertThatThrownBy(() -> repository.deleteTask("target")).isInstanceOf(TaskDeletionException.class);

        assertThat(metadata).exists();
        assertThat(workFile).hasContent("work");
        assertThat(external).hasContent("external");
        assertThat(repository.findTask("target")).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"tasks", "work"})
    void validatesBothTreesCompletelyBeforeDeletingAnyFile(String tree) throws Exception {
        FileFtdcTaskRepository repository = new FileFtdcTaskRepository(directory, new ObjectMapper());
        repository.saveTask(task("target", FtdcTaskStatus.FAILED));
        Path taskDirectory = repository.taskDirectory("target");
        Path workDirectory = Files.createDirectories(repository.workDirectory("target"));
        Path taskFile = Files.writeString(taskDirectory.resolve("zz-keep"), "task");
        Path workFile = Files.writeString(workDirectory.resolve("zz-keep"), "work");
        Path external = Files.writeString(directory.resolve("outside.log"), "external");
        Path link = (tree.equals("tasks") ? taskDirectory : workDirectory).resolve("aa-link");
        Files.createSymbolicLink(link, external);

        assertThatThrownBy(() -> repository.deleteTask("target")).isInstanceOf(TaskDeletionException.class);

        assertThat(taskDirectory.resolve("metadata.json")).exists();
        assertThat(taskFile).hasContent("task");
        assertThat(workFile).hasContent("work");
        assertThat(external).hasContent("external");
        assertThat(repository.findTask("target")).isPresent();
    }

    private FtdcTask task(String id, FtdcTaskStatus status) {
        return new FtdcTask(id, id, status, 1, status == FtdcTaskStatus.QUEUED ? null : 2L, null,
                List.of(new FtdcTaskInputFile("metrics", "0000-metrics", 10)),
                10, 0, 0, 0, 0, null, null, null);
    }
}
