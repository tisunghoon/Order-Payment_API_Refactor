package com.myfave.api.global.lock;

import com.myfave.api.global.error.CustomException;
import com.myfave.api.global.error.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DistributedLockManagerTest {

    @Mock
    private RedissonClient redissonClient;

    private RedissonDistributedLockManager lockManager;

    @BeforeEach
    void setUp() {
        lockManager = new RedissonDistributedLockManager(redissonClient, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("락 획득 성공 시 action 결과를 반환하고 unlock이 호출된다")
    void executeWithLock_success_returnsResultAndUnlocks() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        String result = lockManager.executeWithLock(List.of("key:1"), 3000, 10000, () -> "done");

        assertThat(result).isEqualTo("done");
        verify(lock).unlock();
    }

    @Test
    @DisplayName("tryLock이 false이면 STOCK_LOCK_ACQUIRE_FAILED를 던진다")
    void executeWithLock_timeout_throwsStockLockAcquireFailed() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);

        assertThatThrownBy(() ->
                lockManager.executeWithLock(List.of("key:1"), 3000, 10000, () -> "done"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.STOCK_LOCK_ACQUIRE_FAILED);
    }

    @Test
    @DisplayName("InterruptedException 발생 시 STOCK_LOCK_ACQUIRE_FAILED를 던진다")
    void executeWithLock_interrupted_throwsStockLockAcquireFailed() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenThrow(new InterruptedException());

        assertThatThrownBy(() ->
                lockManager.executeWithLock(List.of("key:1"), 3000, 10000, () -> "done"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.STOCK_LOCK_ACQUIRE_FAILED);
    }

    @Test
    @DisplayName("RedisException 발생 시 STOCK_LOCK_ACQUIRE_FAILED를 던진다")
    void executeWithLock_redisError_throwsStockLockAcquireFailed() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenThrow(new RedisException("connection refused"));

        assertThatThrownBy(() ->
                lockManager.executeWithLock(List.of("key:1"), 3000, 10000, () -> "done"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.STOCK_LOCK_ACQUIRE_FAILED);
    }

    @Test
    @DisplayName("action이 예외를 던져도 finally에서 unlock이 호출된다")
    void executeWithLock_actionThrows_unlockCalledInFinally() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThatThrownBy(() ->
                lockManager.executeWithLock(List.of("key:1"), 3000, 10000, () -> {
                    throw new RuntimeException("action error");
                }));

        verify(lock).unlock();
    }

    @Test
    @DisplayName("중복 키는 하나의 락만 획득한다")
    void executeWithLock_duplicateKeys_acquiresOnce() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock("key:1")).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        lockManager.executeWithLock(List.of("key:1", "key:1"), 3000, 10000, () -> null);

        verify(redissonClient, times(1)).getLock("key:1");
        verify(lock, times(1)).tryLock(anyLong(), anyLong(), any());
        verify(lock, times(1)).unlock();
    }

    @Test
    @DisplayName("복수 키는 오름차순으로 획득하고 내림차순으로 해제한다")
    void executeWithLock_multipleKeys_acquiredAscReleasedDesc() throws InterruptedException {
        RLock lockA = mock(RLock.class, "lockA");
        RLock lockB = mock(RLock.class, "lockB");
        when(redissonClient.getLock("key:1")).thenReturn(lockA);
        when(redissonClient.getLock("key:2")).thenReturn(lockB);
        when(lockA.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lockB.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lockA.isHeldByCurrentThread()).thenReturn(true);
        when(lockB.isHeldByCurrentThread()).thenReturn(true);

        // 역순으로 전달해도 정렬 후 key:1 → key:2 순서로 획득되는지 검증
        lockManager.executeWithLock(List.of("key:2", "key:1"), 3000, 10000, () -> null);

        var acquireOrder = inOrder(lockA, lockB);
        acquireOrder.verify(lockA).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        acquireOrder.verify(lockB).tryLock(anyLong(), anyLong(), any(TimeUnit.class));

        // 해제는 역순: lockB → lockA
        var releaseOrder = inOrder(lockB, lockA);
        releaseOrder.verify(lockB).unlock();
        releaseOrder.verify(lockA).unlock();
    }
}
