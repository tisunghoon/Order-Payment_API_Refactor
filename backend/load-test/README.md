# MyFave Load Test (k6)

5/25 라이브 이벤트 대비 부하 테스트. **D(성훈) 담당** 영역.

상세 설명 / 통과 기준 / 라운드별 비교 표는 노션:
- 실행 계획 — https://www.notion.so/36704552316d802fab3cc1ea7ed818cc
- 검증 방법 — https://www.notion.so/36704552316d802f93d4da55fc3ee8a3

## 디렉토리

```
backend/load-test/
├── k6/
│   ├── scenarios/   # 시나리오 3종 (A/D/E)
│   ├── lib/         # 공통 헬퍼 (http, pool, stomp)
│   └── data/        # tokens.json (gitignored)
├── seed/
│   ├── token-prepare.mjs   # Node 스크립트 — 1000 토큰 사전 생성
│   └── reset.sql           # 라운드 사이 환경 리셋
└── docs/
    └── ws-metrics-contract.md  # C 작업자(Micrometer) 인터페이스
```

## 사전 준비 (한 번만)

```bash
# 1. 인프라
docker-compose up -d
docker-compose -f docker-compose.monitoring.yml up -d

# 2. 백엔드 (loadtest 프로파일 — 1000 유저/Shipping/SaleEvent/ChatRoom 자동 시드)
cd backend
SPRING_PROFILES_ACTIVE=loadtest ./gradlew bootRun

# 3. 토큰 풀 (별도 터미널)
node backend/load-test/seed/token-prepare.mjs
# → backend/load-test/k6/data/tokens.json 생성
```

## 라운드 사이클

```bash
# 매 라운드마다
psql -h localhost -U postgres -d myfave -f backend/load-test/seed/reset.sql

# 시나리오 실행 (택1)
k6 run backend/load-test/k6/scenarios/scenario-a-spike.js
k6 run backend/load-test/k6/scenarios/scenario-d-soldout.js
k6 run backend/load-test/k6/scenarios/scenario-e-chat.js
```

## k6 메트릭을 Grafana로 전송 (라운드 비교 대시보드)

Prometheus는 `--web.enable-remote-write-receiver`가 켜져 있어 k6 메트릭을 직접 수신할 수 있다.
아래처럼 실행하면 `k6_http_*` 지표가 `scenario`/`round` 라벨과 함께 `myfave-loadtest-rounds` 대시보드에 들어온다.

```bash
export BASE_URL=http://localhost:8080/api/v1
export K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write
export K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=true
# 라운드/시나리오 라벨 — k6 메트릭 태그(loadtestTags) + HTTP 헤더(loadtestHeaders→백엔드 MDC)에 동시 부착
export LOADTEST_SCENARIO=D        # A | D | E
export LOADTEST_ROUND=0           # 0 | 1 | 2 | 3
export LOADTEST_RUN_ID="$(date -u +%FT%H%M)-D-r0"

k6 run --out experimental-prometheus-rw \
  backend/load-test/k6/scenarios/scenario-d-soldout.js
```

> `LOADTEST_*`를 비우면 라벨 없이 동작(`k6 run` 단독과 동일). Grafana `$scenario`/`$round` 변수를 채우려면 위 env가 필요하다.
> 분산락 검증 지표 해석은 `MyFave/Talk with AI/k6-분산락-검증-모니터링-지표-가이드.md` 참고.

## 시나리오 D 후처리 검증

```sql
-- PAID 카운트 (기대: 10)
-- 주의: order_items/orders의 PK·FK 컬럼명은 orders_id (order_id 아님)
-- 시드 DB 실제 상품 product_id는 2~11 (1번은 없음)
SELECT COUNT(*) FROM order_items oi
  JOIN orders o ON oi.orders_id = o.orders_id
 WHERE o.order_status = 'PAID';

-- 재고 0 확인
SELECT product_id, stock_quantity, is_soldout
  FROM products WHERE product_id BETWEEN 2 AND 11;

-- 동일 상품 over-selling 검출 (기대: 0행)
SELECT oi.product_id, COUNT(*)
  FROM order_items oi JOIN orders o ON oi.orders_id = o.orders_id
 WHERE o.order_status = 'PAID'
 GROUP BY oi.product_id HAVING COUNT(*) > 1;
```

## 결과 기록 표

| Round | 시나리오 | TPS | p50 | p95 | p99 | 에러율 | DB conn peak | CPU peak | 비고 |
|---|---|---|---|---|---|---|---|---|---|
| 0 | A |   |   |   |   |   |   |   | 베이스라인 |
| 0 | D |   |   |   |   |   |   |   | over-selling 0건 |
| 0 | E |   |   |   |   |   |   |   | sessions=1000 |
| 1 | A |   |   |   |   |   |   |   | 인덱스 적용 |
| 1 | D |   |   |   |   |   |   |   |   |
| 1 | E |   |   |   |   |   |   |   |   |
| 2 | A/D/E | ... | ... | ... | ... | ... | ... | ... | N+1 제거 |
| 3 | A/D/E | ... | ... | ... | ... | ... | ... | ... | 캐싱 |

각 라운드 측정 후 노션에 동기화.

## 의존성

- A(현영) EC2: 의존 없음 (로컬에서 측정)
- B(항중) Prometheus/Loki/Tempo: `docker-compose.monitoring.yml` 기동 필요
- C(정수) Grafana + Micrometer: 시나리오 E는 `docs/ws-metrics-contract.md` 메트릭 적용 후 측정
