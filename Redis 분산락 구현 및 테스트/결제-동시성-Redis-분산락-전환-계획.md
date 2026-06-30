# 결제 동시성 제어: 비관적 락 → Redis 분산락(Redisson) 전환

## Context (왜 이 변경을 하는가)

MyFave는 **재고 1개짜리 단일 상품**을 파는 인플루언서 플리마켓이다. 현재는 결제 승인 시점에 `findByIdForUpdate`(`@Lock(PESSIMISTIC_WRITE)`)로 상품 row를 잠가 over-selling을 막는다. 정합성은 보장되지만, **인기 상품에 결제 트래픽이 몰리면 같은 row 락을 기다리는 스레드들이 DB 커넥션을 점유한 채 대기** → HikariCP 풀(기본 10) 고갈 → DB 부하 폭증이라는 구조적 문제가 있다.

해결책: 직렬화 책임을 DB row 락에서 **Redis 분산락**으로 옮긴다. 락 대기를 Redis(pub/sub)에서 처리하므로 **대기 중 DB 커넥션을 점유하지 않고**, 실제 락을 획득한 1개 스레드만 짧은 DB 트랜잭션으로 재고를 차감한다. 결과적으로 커넥션 풀 고갈이 해소되고, over-selling 방지는 그대로 유지된다.

**확정 결정**: ① 라이브러리 = **Redisson 추가** · ② 락 실패 정책 = **짧게 대기 후 실패(waitTime ~3s, 실패 시 PG 자동환불 보상)** · ③ DB 안전망 = **분산락만 사용**(`@Version` 미도입, 후속 과제로만 기록).

## 진단: 현재 동시성 구조

- `confirmPayment`은 `@Transactional(propagation = NOT_SUPPORTED)`(트랜잭션 없음) 오케스트레이터. PG 외부호출은 트랜잭션 밖에서 처리하고, 재고 차감만 `self.decreaseStockForConfirm()`(`@Lazy` 프록시 → 별도 `@Transactional`)으로 호출.
- `decreaseStockForConfirm`은 `findByIdForUpdate`(`SELECT … FOR UPDATE`)로 상품 row 락 획득. 데드락 회피를 위해 productId ASC 정렬 후 순차 락. 취소 시 재고 복구(`applyCancelResult`)도 동일하게 비관적 락 사용.
- **문제의 정체**: row 락 대기 스레드들이 **대기 내내 DB 커넥션을 점유** → 재고 1개 인기 상품에 결제가 몰리면 같은 row에 직렬화되어 HikariCP 풀 고갈 → DB 부하 폭증.
- **유리한 점**: 오케스트레이터가 이미 트랜잭션 밖에 있어 "락 획득 → 짧은 차감 트랜잭션 커밋 → 락 해제" 패턴을 깔끔히 적용 가능. Redis 인프라(`spring-boot-starter-data-redis`, `RedisConfig`)는 이미 chat·auth에서 사용 중(단 Redisson·`@DistributedLock`은 없음).

## 핵심 설계 원칙 — 락 경계는 트랜잭션 바깥

```
[confirmPayment, 트랜잭션·DB커넥션 없음]
  락 획득(Redis 대기 — 커넥션 점유 X)
    → self.decreaseStockForConfirm()  // 여기서 트랜잭션 시작 → 재고 차감 → 커밋 완료 후 리턴
  finally 락 해제                       // 커밋 이후 해제 보장
```

프록시 경유 `@Transactional` 메서드는 **정상 리턴 시점 = 커밋 완료 시점**이므로, 람다가 반환된 직후 finally의 unlock은 항상 커밋 뒤에 일어난다. 다른 스레드가 락을 새로 얻어 읽을 때는 직전 커밋 결과(재고 0)를 본다 → over-selling/lost update 불가.

**안티패턴(반드시 회피)**: 락을 `decreaseStockForConfirm` *내부*(트랜잭션 안)에서 잡으면 안 된다. unlock이 커밋보다 먼저 일어날 수 있고, 락 대기 중 커넥션을 점유해 원래 문제가 재발한다. 락은 반드시 오케스트레이터(트랜잭션 바깥)에서 잡는다.

## 변경 파일

### 신규
| 경로 | 내용 |
|---|---|
| `backend/.../global/lock/DistributedLockManager.java` | 인터페이스: `<T> T executeWithLock(List<String> keys, long waitMs, long leaseMs, Supplier<T> action)` + Runnable 오버로드 |
| `backend/.../global/lock/RedissonDistributedLockManager.java` | Redisson 구현: 키 정렬 → 순차 `tryLock` → `action.get()` → finally 역순 `unlock`(`isHeldByCurrentThread` 가드) → 예외/메트릭 |
| `backend/.../global/lock/LockKeys.java` | `lock:product:stock:{productId}` 네이밍 유틸 |
| `backend/.../global/config/RedissonConfig.java` | `RedissonClient` 빈(single server, 기존 `spring.data.redis.*` 재사용, password 옵셔널) |
| `backend/src/test/.../payment/service/PaymentStockConcurrencyTest.java` | 동시 결제 → 정확히 1건 성공 통합 테스트 |
| `backend/src/test/.../global/lock/DistributedLockManagerTest.java` | 락 매니저 단위 테스트 |

### 수정
| 경로 | 변경 요지 |
|---|---|
| `backend/build.gradle` | Redisson + (테스트) Testcontainers Redis 의존성 추가 |
| `backend/.../global/error/ErrorCode.java` | `STOCK_LOCK_ACQUIRE_FAILED(409, "재고 처리 락 획득 실패 — 잠시 후 다시 시도해 주세요")` 추가 (상품 섹션 L43 인근) |
| `backend/.../domain/payment/service/PaymentService.java` | 오케스트레이터에 분산락 도입, 비관적 락 호출 제거 (아래 상세) |
| `backend/.../domain/product/repository/ProductRepository.java` | `findByIdForUpdate`에 `@Deprecated` 표기 (호출부 제거 후, 삭제는 후속 PR) |

## 의존성 (build.gradle)

```gradle
// Redis 분산락 (Redisson — pub/sub 대기 + watchdog)
implementation 'org.redisson:redisson-spring-boot-starter:3.37.0'
// (테스트) 분산락 동시성 통합 테스트
testImplementation 'org.testcontainers:junit-jupiter:1.20.4'
testImplementation 'com.redis:testcontainers-redis:2.2.2'
```
> Redisson은 별도 커넥션을 쓰며 기존 Lettuce 기반 `RedisTemplate`/chat pub/sub/auth refresh-token과 공존한다. `RedissonConfig`로 `RedissonClient`만 명시 정의해 자동설정 충돌을 피한다.

## DistributedLockManager 구현 스케치 (Redisson)

```java
public <T> T executeWithLock(List<String> keys, long waitMs, long leaseMs, Supplier<T> action) {
    List<String> ordered = keys.stream().distinct().sorted().toList(); // 데드락 회피
    Deque<RLock> acquired = new ArrayDeque<>();
    Timer.Sample wait = Timer.start(meterRegistry);
    try {
        for (String key : ordered) {
            RLock lock = redissonClient.getLock(key);
            boolean ok = lock.tryLock(waitMs, leaseMs, TimeUnit.MILLISECONDS); // InterruptedException 처리
            if (!ok) {
                meterRegistry.counter("myfave.stock.lock.acquire", "outcome", "timeout").increment();
                throw new CustomException(ErrorCode.STOCK_LOCK_ACQUIRE_FAILED);
            }
            acquired.push(lock);
        }
        meterRegistry.counter("myfave.stock.lock.acquire", "outcome", "success").increment();
        wait.stop(Timer.builder("myfave.stock.lock.wait.duration").register(meterRegistry)); wait = null;
        return action.get();                       // ← 여기서 @Transactional 시작·커밋 완료
    } catch (RedisException e) {
        meterRegistry.counter("myfave.stock.lock.acquire", "outcome", "redis_error").increment();
        throw new CustomException(ErrorCode.STOCK_LOCK_ACQUIRE_FAILED);
    } finally {
        if (wait != null) wait.stop(Timer.builder("myfave.stock.lock.wait.duration").register(meterRegistry));
        while (!acquired.isEmpty()) { RLock l = acquired.pop(); if (l.isHeldByCurrentThread()) l.unlock(); }
    }
}
```
- **타임아웃**: `waitMs = 3000`(짧게 대기 후 실패), `leaseMs = 10000`(재고 차감 트랜잭션 최악시간+마진; leaseTime < 트랜잭션 시간이면 조기 만료 위험이므로 충분히 크게). 프로퍼티 `myfave.lock.product-stock.{wait-ms,lease-ms}`로 외부화.
- 키별 동일 `waitMs`로 순차 획득(단일 상품 위주라 단순화). CART 다상품 빈번해지면 "전역 deadline"으로 확장 여지.

## RedissonConfig 스케치

```java
@Bean(destroyMethod = "shutdown")
public RedissonClient redissonClient() {
    Config config = new Config();
    var single = config.useSingleServer()
        .setAddress("redis://" + host + ":" + port)
        .setConnectionPoolSize(16).setConnectionMinimumIdleSize(4)
        .setTimeout(3000).setRetryAttempts(2);
    if (password != null && !password.isBlank()) single.setPassword(password);
    return Redisson.create(config);
}
```
`@Value("${spring.data.redis.host}")`, `port`, `${spring.data.redis.password:}`(옵셔널) 사용. 테스트는 `@DynamicPropertySource`로 Testcontainers host/port 주입.

## PaymentService 수정

**1) Context에 정렬된 productIds 적재** — 오케스트레이터가 락 키를 알도록:
```java
public record ConfirmContext(Long paymentId, int totalPaymentPrice, List<Long> sortedProductIds) {}
// validateForConfirm: orderItemRepository.findByOrder(order) → productId ASC 정렬해 Context에 포함
```
`CancelContext`도 동일하게 `List<Long> sortedProductIds` 추가, `validateForCancel`에서 채운다.

**2) confirmPayment** — 재고 차감 호출을 분산락으로 감싼다:
```java
try {
    List<String> keys = LockKeys.productStocks(ctx.sortedProductIds());
    lockManager.executeWithLock(keys, lockWaitMs, lockLeaseMs,
        () -> self.decreaseStockForConfirm(ctx.paymentId())); // self 프록시 필수
} catch (CustomException e) {
    // STOCK_LOCK_ACQUIRE_FAILED / PRODUCT_SOLD_OUT / STOCK_INSUFFICIENT → 동일 보상 경로
    // (PG 자동환불 + failConfirm + 전파) — 기존 catch 블록 그대로 재사용
}
```
> catch 타입을 기존 `CustomException`으로 유지하면 **락 획득 실패가 기존 재고차감 실패 보상 경로(PG 이미 PAID → 자동환불)에 자연 합류**한다. 새 분기 불필요.

**3) decreaseStockForConfirm** — 비관적 락 제거:
```java
Product product = productRepository.findByProductIdAndDeletedAtIsNull(pid)  // findByIdForUpdate → 동치 메서드(이미 존재)
        .orElseThrow(() -> new CustomException(ErrorCode.PRODUCT_NOT_FOUND));
product.decreaseStock(1);
// myfave.stock.deduct.attempt{outcome} 카운터 유지
```
기존 `PessimisticLockingFailureException` catch와 `myfave.stock.lock.wait.duration`/`myfave.stock.deadlock` 측정은 제거(대기시간 측정 책임은 LockManager로 이관, deadlock→`lock.acquire{outcome=timeout/redis_error}`로 대체).

**4) cancelPayment / applyCancelResult** — 전액 취소(재고 복구)만 동일 패턴 적용:
```java
if (ctx.fullCancel()) {
    lockManager.executeWithLock(LockKeys.productStocks(ctx.sortedProductIds()), lockWaitMs, lockLeaseMs,
        () -> self.applyCancelResult(...));
} else { self.applyCancelResult(...); }   // 부분취소는 재고 복구 없음 → 락 불필요
// applyCancelResult 내부: findByIdForUpdate → findByProductIdAndDeletedAtIsNull, increaseStock(1) 유지
```
> 웹훅(`completeWebhook`)·reconcile은 재고를 건드리지 않으므로 락 불필요(기존 멱등 방어 유지).

## 메트릭 매핑 (관측성 보존)

| 기존 | 신규 | 위치 |
|---|---|---|
| `myfave.stock.lock.wait.duration` | **이름 유지** (DB대기→Redis대기 의미 전환) | LockManager |
| `myfave.stock.deadlock` | `myfave.stock.lock.acquire{outcome=timeout/redis_error}` | LockManager |
| `myfave.stock.deduct.attempt{outcome}` | **유지** | decreaseStockForConfirm |
| `myfave.payment.stock.deduct.failed{reason}` | 유지(reason에 `STOCK_LOCK_ACQUIRE_FAILED` 추가) | confirmPayment |
| `myfave.stock.snapshot`(over-sell 검증 Gauge) | 유지 | MetricsConfig |

부하테스트 입증: 동일 부하(`application-loadtest`, pool=50, 시나리오 D)에서 `hikaricp.connections.pending`/`active`가 비관적 락 대비 현저히 낮아짐 + `myfave.stock.snapshot==0` 수렴(over-sell 0).

## 구현 순서 (빌드 안 깨지게)

1. `build.gradle` 의존성 추가 → `./gradlew build -x test`로 해소 확인
2. `RedissonConfig` 추가 → `bootRun`(local) 부팅 + chat/auth 회귀 확인
3. `ErrorCode.STOCK_LOCK_ACQUIRE_FAILED` 추가
4. `LockKeys` + `DistributedLockManager`(+Redisson 구현) 추가 — 여기까지 PaymentService 무변경 → 빌드 그린
5. `DistributedLockManagerTest` 작성·통과
6. `PaymentService` 수정(Context 확장 → confirm/cancel에 `executeWithLock` → 호출부 `findByIdForUpdate`→`findByProductIdAndDeletedAtIsNull`) — 기존 단위테스트는 LockManager mock으로 회귀
7. `PaymentStockConcurrencyTest` 추가·통과
8. `ProductRepository.findByIdForUpdate`에 `@Deprecated` 표기(삭제는 후속 PR)

## 검증 (Verification)

- **동시성 통합 테스트**: 재고 1 상품에 N 스레드가 `confirmPayment` 동시 호출(`CountDownLatch`+`ExecutorService`) → 성공 정확히 1건, 나머지 `STOCK_LOCK_ACQUIRE_FAILED`/`PRODUCT_SOLD_OUT`, 최종 `stockQuantity==0 && isSoldout==true`. Testcontainers Redis + `@DynamicPropertySource`.
- **LockManager 단위 테스트**: 정렬·역순해제·재진입·timeout 예외, 키 2개 교차 시 데드락 없음.
- **회귀(Redis 무의존)**: 기존 PaymentService 테스트는 `DistributedLockManager` mock(`action.get()` 즉시 실행 stub)으로 보상 경로/메트릭 검증.
- **빌드**: `cd backend && ./gradlew build`
- **부하테스트**: k6 시나리오 D before(pessimistic)/after(distributed) — `hikaricp.connections.pending` p99, `myfave.stock.lock.wait.duration`, `myfave.stock.snapshot` 비교.

## 리스크 / 엣지케이스

- **Redis 단일 장애점**: 다운 시 모든 차감이 `STOCK_LOCK_ACQUIRE_FAILED`→PG 자동환불(정합성 유지, 가용성 저하). 완화: Redis HA(Sentinel/Cluster) + `lock.acquire{outcome=redis_error}` 알람.
- **leaseTime < 트랜잭션 시간**: 락 조기 만료 → over-selling. leaseMs를 트랜잭션 최악시간+마진(10s)으로, 운영 중 트랜잭션 길이 모니터링.
- **프록시 자기호출 함정**: 람다 안에서 반드시 `self.`(프록시) 호출. `this.`로 부르면 트랜잭션 미적용 → 패턴 붕괴. 코드리뷰 체크포인트.
- **CART 다상품 waitTime 누적**: 키별 순차 대기 시 최악 N×waitMs. 단일상품 위주라 영향 작음.

## 후속 과제 (이번 범위 밖 — "분산락만" 선택)

- `Product`에 `@Version` 낙관적 락 안전망(Redis 장애/조기만료 시 DB 레벨 이중차감 최후 방어) — 스키마 마이그레이션 + 상품 수정 경로 충돌 핸들링 동반이라 별도 PR.
- `ProductRepository.findByIdForUpdate` 완전 제거.
