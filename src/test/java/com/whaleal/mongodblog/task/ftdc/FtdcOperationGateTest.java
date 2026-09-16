package com.whaleal.mongodblog.task.ftdc;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FtdcOperationGateTest {
    @Test
    void serializesOperationsAndReleasesPermitAfterFailure() throws Exception {
        FtdcOperationGate gate = new FtdcOperationGate();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> gate.call(() -> {
                int current = active.incrementAndGet();
                maximum.accumulateAndGet(current, Math::max);
                started.countDown();
                Thread.sleep(40);
                active.decrementAndGet();
                return 1;
            }));
            var second = executor.submit(() -> gate.call(() -> {
                int current = active.incrementAndGet();
                maximum.accumulateAndGet(current, Math::max);
                started.countDown();
                active.decrementAndGet();
                return 2;
            }));
            assertThat(first.get()).isEqualTo(1);
            assertThat(second.get()).isEqualTo(2);
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
        assertThat(maximum).hasValue(1);

        try {
            gate.call(() -> { throw new IllegalStateException("boom"); });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("boom");
        }
        assertThat(gate.call(() -> "released")).isEqualTo("released");
    }
}
