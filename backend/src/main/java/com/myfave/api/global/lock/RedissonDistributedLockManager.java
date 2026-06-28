package com.myfave.api.global.lock;

import com.myfave.api.global.error.CustomException;
import com.myfave.api.global.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedissonDistributedLockManager implements DistributedLockManager {

    private final RedissonClient redissonClient;
    private final MeterRegistry meterRegistry;

    @Override
    public <T> T executeWithLock(List<String> keys, long waitTimeMs, long leaseTimeMs, Supplier<T> action) {
        // 데드락 회피: 항상 동일한 전역 순서(오름차순)로 락 획득
        List<String> ordered = keys.stream().distinct().sorted().toList();
        Deque<RLock> acquired = new ArrayDeque<>();
        Timer.Sample waitSample = Timer.start(meterRegistry);

        try {
            for (String key : ordered) {
                RLock lock = redissonClient.getLock(key);
                boolean ok;
                try {
                    ok = lock.tryLock(waitTimeMs, leaseTimeMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    meterRegistry.counter("myfave.stock.lock.acquire", "outcome", "interrupted").increment();
                    throw new CustomException(ErrorCode.STOCK_LOCK_ACQUIRE_FAILED);
                }

                if (!ok) {
                    meterRegistry.counter("myfave.stock.lock.acquire", "outcome", "timeout").increment();
                    throw new CustomException(ErrorCode.STOCK_LOCK_ACQUIRE_FAILED);
                }
                acquired.push(lock); // stack: 역순 해제용
            }

            meterRegistry.counter("myfave.stock.lock.acquire", "outcome", "success").increment();
            waitSample.stop(Timer.builder("myfave.stock.lock.wait.duration").register(meterRegistry));
            waitSample = null;

            return action.get(); // ← 이 안에서 @Transactional 시작·커밋 완료 후 리턴
        } catch (RedisException e) {
            meterRegistry.counter("myfave.stock.lock.acquire", "outcome", "redis_error").increment();
            log.error("[Lock] Redis 장애로 락 획득 실패: keys={}, error={}", keys, e.getMessage(), e);
            throw new CustomException(ErrorCode.STOCK_LOCK_ACQUIRE_FAILED);
        } finally {
            if (waitSample != null) {
                waitSample.stop(Timer.builder("myfave.stock.lock.wait.duration").register(meterRegistry));
            }
            // 획득 역순으로 해제, 보유 중인 경우에만
            while (!acquired.isEmpty()) {
                RLock lock = acquired.pop();
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (Exception ex) {
                    // leaseTime 만료로 이미 해제된 경우 — 경고만 기록
                    log.warn("[Lock] unlock 실패 (만료 또는 미보유): key={}, error={}", lock.getName(), ex.getMessage());
                }
            }
        }
    }
}
