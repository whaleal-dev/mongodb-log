package com.whaleal.mongodblog.task.ftdc;

import com.whaleal.mongodblog.storage.ftdc.FtdcTaskRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class FtdcTaskService {
    public static final int MAX_FILES = 20;

    private final FtdcTaskRepository repository;
    private final FtdcTaskRunner runner;
    private final Executor executor;

    public FtdcTaskService(FtdcTaskRepository repository, FtdcTaskRunner runner,
                           @Qualifier("taskExecutor") Executor executor) {
        this.repository = repository;
        this.runner = runner;
        this.executor = executor;
    }

    public FtdcTask create(String name, List<MultipartFile> files) {
        validateFiles(files);
        String id = UUID.randomUUID().toString().replace("-", "");
        Path work = repository.workDirectory(id);
        Path source = work.resolve("source");
        List<FtdcTaskInputFile> inputs = new ArrayList<>();
        try {
            Files.createDirectories(source);
            for (int index = 0; index < files.size(); index++) {
                MultipartFile file = files.get(index);
                String originalName = safeFileName(file.getOriginalFilename(), index);
                String storedName = "%04d-%s".formatted(index, originalName);
                Path target = source.resolve(storedName).normalize();
                if (!target.startsWith(source)) throw new IllegalArgumentException("FTDC 文件名包含非法路径");
                try (InputStream input = file.getInputStream()) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
                inputs.add(new FtdcTaskInputFile(originalName, storedName, Files.size(target)));
            }
        } catch (IOException | RuntimeException e) {
            cleanup(work);
            throw new IllegalStateException("保存 FTDC 上传文件失败", e);
        }
        long totalBytes = inputs.stream().mapToLong(FtdcTaskInputFile::sizeBytes).sum();
        String taskName = name == null || name.isBlank() ? inputs.get(0).originalName() : name.trim();
        FtdcTask task = new FtdcTask(id, taskName, FtdcTaskStatus.QUEUED, System.currentTimeMillis(), null,
                null, inputs, totalBytes, 0, 0, 0, 0, null, null, null);
        repository.saveTask(task);
        executor.execute(() -> runner.run(id));
        return task;
    }

    public FtdcTask get(String id) {
        return repository.findTask(id).orElseThrow(() -> new NoSuchElementException("FTDC 任务不存在：" + id));
    }

    public List<FtdcTask> list() {
        return repository.listTasks();
    }

    public void delete(String id) {
        repository.deleteTask(id);
    }

    private void validateFiles(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("请至少选择一个 FTDC 文件");
        if (files.size() > MAX_FILES) throw new IllegalArgumentException("一个 FTDC 任务最多上传 20 个文件");
        if (files.stream().anyMatch(file -> file == null || file.isEmpty())) {
            throw new IllegalArgumentException("FTDC 文件不能为空");
        }
    }

    private String safeFileName(String originalName, int index) {
        if (originalName == null || originalName.isBlank()) return "metrics-" + index;
        String normalized = originalName.replace('\\', '/');
        String base = normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\r\\n]", "_");
        return base.isBlank() ? "metrics-" + index : base;
    }

    private void cleanup(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {
            directory.toFile().deleteOnExit();
        }
    }
}
