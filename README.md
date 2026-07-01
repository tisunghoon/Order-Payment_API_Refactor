# MyFave

1인 인플루언서 플리마켓 쇼핑몰 백엔드.

---

## 결제 재고 차감 동시성 제어 — Redis 분산락

### 배경 · 문제

한정 수량 상품에 결제 트래픽이 몰리면 여러 요청이 동시에 재고를 차감해 **over-selling**(재고보다 많은 판매)이 발생할 수 있다.

기존에는 DB 비관적 락(`@Lock(PESSIMISTIC_WRITE)`, `SELECT ... FOR UPDATE`)으로 막았으나, **같은 row 락을 기다리는 스레드가 DB 커넥션을 점유한 채 대기**해 HikariCP 커넥션 풀(기본 10)이 고갈되는 구조적 한계가 있었다.

→ 직렬화 책임을 **DB row 락에서 Redis 분산락(Redisson)** 으로 옮겨, 락 대기를 Redis(pub/sub)가 흡수하도록 전환했다. **대기 중에는 DB 커넥션을 점유하지 않으며**, over-selling 방지는 그대로 유지한다.

### 설계 핵심 — 락 경계는 트랜잭션 "바깥"

이 전환의 정확성을 좌우하는 불변식: **락은 트랜잭션을 감싸야 하고, 트랜잭션 안에서 잡으면 안 된다.**

```
[confirmPayment]  @Transactional(NOT_SUPPORTED)   ← 트랜잭션·DB커넥션 없는 오케스트레이터
  └─ lockManager.executeWithLock(keys, wait, lease, action)   ← Redis 락 대기 (커넥션 점유 X)
        └─ self.decreaseStockForConfirm(paymentId)   ← @Transactional 시작 → 재고 차감 → 커밋 완료 후 리턴
  └─ finally: 획득 역순으로 unlock                    ← 커밋 이후 해제 보장
```

- `confirmPayment`는 `@Transactional(propagation = NOT_SUPPORTED)` 오케스트레이터(트랜잭션 없음).
- 재고 차감은 `self.`(프록시) 경유 호출이라 **별도 `@Transactional`이 시작**되고, 정상 리턴 시점 = 커밋 완료 시점이다. 따라서 락 해제(finally)는 항상 커밋 이후에 일어난다.
- **안티패턴(회피)**: 락을 `decreaseStockForConfirm` *내부*에서 잡으면 unlock이 커밋보다 먼저 일어날 수 있고, 락 대기 중 DB 커넥션을 점유해 원래 문제가 재발한다.

### 구현 구성요소

| 파일 | 역할 |
|------|------|
| `global/config/RedissonConfig.java` | `RedissonClient` 빈. 단일 서버, connectionPool 16, timeout 3s, retry 2 |
| `global/lock/DistributedLockManager.java` | 락 매니저 인터페이스 — `executeWithLock(keys, waitMs, leaseMs, action)` |
| `global/lock/RedissonDistributedLockManager.java` | Redisson 구현체 — 정렬 획득·역순 해제·outcome 메트릭 |
| `global/lock/LockKeys.java` | 키 네이밍 — `lock:product:stock:{productId}` |
| `domain/payment/service/PaymentService.java` | 오케스트레이터에서 락 획득 후 재고 차감 |
| `global/error/ErrorCode.java` | `STOCK_LOCK_ACQUIRE_FAILED`(409) |

의존성: `org.redisson:redisson-spring-boot-starter:3.37.0` (pub/sub 대기 + watchdog 자동 갱신)

### 동시성 안전장치

- **데드락 회피**: 복수 상품 결제 시 락 키를 `productId` **오름차순으로 정렬**해 순차 획득하고, **역순으로 해제**한다(전역 획득 순서 고정).
- **락 실패 정책**: `waitTime` 동안 획득 못 하면 짧게 대기 후 실패(`STOCK_LOCK_ACQUIRE_FAILED`). 모든 키를 획득한 경우에만 재고를 차감한다.
- **누수 방지**: `leaseTime`(watchdog)으로 락 자동 만료. 해제 시 `isHeldByCurrentThread`를 확인해 남의 락을 풀지 않는다.
- **보상 트랜잭션**: 결제(PG PAID) 후 재고 차감이 실패하면 PG 자동 환불 + `Payment` FAILED 처리로 정합성을 되돌린다.

기본 파라미터 (`application.yml` override 가능):

```yaml
myfave:
  lock:
    product-stock:
      wait-ms: 3000    # 락 획득 최대 대기
      lease-ms: 10000  # 락 보유 만료(watchdog)
```

### 관측 지표 (Micrometer → Prometheus/Grafana)

| 메트릭 | 태그 | 용도 |
|--------|------|------|
| `myfave.stock.lock.acquire` | `outcome`=success/timeout/interrupted/redis_error | 락 획득 결과 분포 |
| `myfave.stock.lock.wait.duration` | — | 락 대기 시간 p50/p95/p99 |
| `myfave.stock.deduct.attempt` | `outcome`=success/sold_out/insufficient | 재고 차감 결과 분포 |

### 검증 — 부하 테스트 (시나리오 D)

k6로 **300 VU가 재고 1개짜리 상품 10종을 동시에 경쟁**시켜 정합성을 검증한다.

- 기대: **정확히 10건만 결제 성공, over-selling 0건**
- 결과: k6 임계값(`soldout_confirm_success == 10`, `oversell == 0`)·DB 사후 검증(PAID 10건, 재고 합 0, over-sell 0행) 모두 통과, 2회 연속 재현.

실행 방법은 `backend/load-test/README.md`, 상세 결과는 `Redis 분산락 구현 및 테스트/부하테스트-결과-정리.md` 참고.
