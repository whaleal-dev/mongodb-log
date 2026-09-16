package com.whaleal.mongodblog.storage.ftdc;

import com.whaleal.mongodblog.task.ftdc.FtdcTask;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface FtdcTaskRepository {
    void saveTask(FtdcTask task);
    Optional<FtdcTask> findTask(String id);
    List<FtdcTask> listTasks();
    void deleteTask(String id);
    FtdcCatalog readCatalog(String id);
    Path taskDirectory(String id);
    Path workDirectory(String id);
}
