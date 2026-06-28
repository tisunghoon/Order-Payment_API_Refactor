package com.myfave.api.global.lock;

import java.util.List;
import java.util.function.Supplier;

public interface DistributedLockManager {

    /**
     * keys를 정렬하여 순차로 tryLock 후 action 실행.
     * 모든 락 획득 성공 시에만 action 수행, finally에서 역순 해제.
     * 락 획득 실패 시 STOCK_LOCK_ACQUIRE_FAILED CustomException 발생.
     */
    <T> T executeWithLock(List<String> keys, long waitTimeMs, long leaseTimeMs, Supplier<T> action);

    default void executeWithLock(List<String> keys, long waitTimeMs, long leaseTimeMs, Runnable action) {
        executeWithLock(keys, waitTimeMs, leaseTimeMs, () -> {
            action.run();
            return null;
        });
    }
}
