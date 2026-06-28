package com.myfave.api.domain.payment.service;

import com.myfave.api.global.error.CustomException;
import com.myfave.api.global.lock.LockKeys;
import com.myfave.api.global.lock.RedissonDistributedLockManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PaymentStockConcurrencyTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private RedissonClient redissonClient;
    private RedissonDistributedLockManager lockManager;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379))
                .setTimeout(3000)
                .setRetryAttempts(0);
        redissonClient = Redisson.create(config);
        lockManager = new RedissonDistributedLockManager(redissonClient, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        redissonClient.shutdown();
    }

    @Test
    @DisplayName("동시 N개 스레드에서 재고 1개 상품은 정확히 1건만 차감된다")
    void concurrentDecrease_stockOne_exactlyOneSucceeds() throws InterruptedException {
        int threadCount = 8;
        AtomicInteger stock = new AtomicInteger(1);
        AtomicInteger decrementCount = new AtomicInteger(0);
        List<String> keys = List.of(LockKeys.productStock(100L));

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    lockManager.executeWithLock(keys, 2000, 5000, () -> {
                        if (stock.get() > 0) {
                            stock.decrementAndGet();
                            decrementCount.incrementAndGet();
                        }
                        return null;
                    });
                } catch (CustomException | InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(decrementCount.get()).isEqualTo(1);
        assertThat(stock.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("락 대기시간 초과 시 STOCK_LOCK_ACQUIRE_FAILED가 발생한다")
    void lockTimeout_throwsStockLockAcquireFailed() throws InterruptedException {
        List<String> keys = List.of(LockKeys.productStock(200L));
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch lockReleased = new CountDownLatch(1);

        // 스레드 1: 락을 획득하고 신호를 보낸 뒤 잠시 점유
        Thread holder = new Thread(() -> {
            try {
                lockManager.executeWithLock(keys, 3000, 5000, () -> {
                    lockHeld.countDown();
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (Exception ignored) {
            } finally {
                lockReleased.countDown();
            }
        });
        holder.start();

        // 스레드 1이 락을 잡을 때까지 대기
        lockHeld.await(5, TimeUnit.SECONDS);

        // 스레드 2: waitTime = 200ms → 락 점유 중이므로 timeout
        assertThatThrownBy(() ->
                lockManager.executeWithLock(keys, 200, 5000, () -> null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("STOCK_LOCK_ACQUIRE_FAILED");

        lockReleased.await(5, TimeUnit.SECONDS);
        holder.join();
    }
}
