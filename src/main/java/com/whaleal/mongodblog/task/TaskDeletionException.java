package com.whaleal.mongodblog.task;

public final class TaskDeletionException extends RuntimeException {
    public TaskDeletionException(String message, Throwable cause) {
        super(message, cause);
    }
}
