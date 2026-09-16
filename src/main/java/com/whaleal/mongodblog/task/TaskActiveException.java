package com.whaleal.mongodblog.task;

public final class TaskActiveException extends RuntimeException {
    public TaskActiveException(String message) {
        super(message);
    }
}
