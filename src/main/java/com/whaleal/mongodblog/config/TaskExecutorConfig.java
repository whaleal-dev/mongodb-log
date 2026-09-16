package com.whaleal.mongodblog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class TaskExecutorConfig {
    @Bean(name = "taskExecutor", destroyMethod = "shutdown")
    public ExecutorService taskExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mongodb-log-task-runner");
            thread.setDaemon(true);
            return thread;
        });
    }
}

