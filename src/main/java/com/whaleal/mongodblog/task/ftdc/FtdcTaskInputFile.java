package com.whaleal.mongodblog.task.ftdc;

public record FtdcTaskInputFile(String originalName, String storedName, long sizeBytes) {
}
