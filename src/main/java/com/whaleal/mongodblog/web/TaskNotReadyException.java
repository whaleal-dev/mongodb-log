package com.whaleal.mongodblog.web;

public final class TaskNotReadyException extends RuntimeException {
    public TaskNotReadyException(String message) {
        super(message);
    }
}

