package com.whaleal.mongodblog.task;

public record TaskInputFile(
        String originalName,
        String storedName,
        long sizeBytes
) {
}

