package com.whaleal.mongodblog.task.ftdc;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

@Component
public final class FtdcOperationGate {
    private final Semaphore permit = new Semaphore(1, true);

    public <T> T call(InterruptibleOperation<T> operation) throws Exception {
        permit.acquire();
        try {
            return operation.run();
        } finally {
            permit.release();
        }
    }

    public void run(InterruptibleAction action) throws Exception {
        call(() -> {
            action.run();
            return null;
        });
    }

    @FunctionalInterface
    public interface InterruptibleOperation<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    public interface InterruptibleAction {
        void run() throws Exception;
    }
}
