# 채팅 roomId 설계 논의

> 작성일: 2026-04-15
> 목적: roomId 제거 가능 여부를 팀원과 논의하기 위한 현황 정리

---

## 1. 왜 지금 roomId가 있는가

### DB 설계 의도

`chat_rooms` 테이블은 **판매 이벤트(sale_events) 1개당 채팅방 1개**를 기준으로 설계되어 있다.

```
sale_events (1) ──── (1) chat_rooms
```

- `chat_rooms.sale_id` → `sale_events.sale_id` FK
- DB 코멘트: `"판매 이벤트 시작 30분 전부터 운영하는 판매자-고객 소통 채팅방"`
- 판매 이벤트가 끝나면 `is_active = FALSE`, `closed_at` 기록 후 방 종료

### 1인 인플루언서 플리마켓 특성

- 인플루언서 1명이 운영하는 쇼핑몰이므로 판매 이벤트가 동시에 여러 개 열리지 않는다.
- 따라서 현실적으로 **활성 채팅방은 항상 1개**다.

---

## 2. roomId가 현재 코드에서 하는 일

현재 roomId는 **DB 조회에 쓰이지 않는다**. 오직 Redis 키/채널 구분용으로만 사용된다.

| 위치 | roomId 용도 | 실제 값 예시 |
|------|-------------|-------------|
| `@MessageMapping("/chat/{roomId}")` | STOMP 라우팅 수신 | `/app/chat/1` |
| `ChatMessageService` Redis 히스토리 키 | 채팅 내역 저장/조회 | `chat:history:1` |
| `RedisPublisher` 채널명 | Pub/Sub 발행 | `chat:room:1` |
| `RedisSubscriber` 채널 파싱 | 채널에서 roomId 추출 후 브로드캐스트 | `/topic/chat/1` |
| `ChatEventListener` destination 파싱 | 구독 감지 + 참여자 수 관리 | `/topic/chat/1` |
| `SessionRegistry` Map 키 | 세션-방 매핑 | `Map<Long, Set<String>>` |

---

## 3. roomId 제거 시 변경 범위

활성 채팅방이 항상 1개라는 전제 하에 roomId를 없애면 클라이언트는 방 ID를 알 필요가 없고,
서버가 DB에서 활성 방을 직접 조회해 내부적으로만 사용한다.

**변경 전 → 후 비교:**

```
[클라이언트]
변경 전: /app/chat/1 으로 발행, /topic/chat/1 구독
변경 후: /app/chat 으로 발행, /topic/chat 구독

[서버 내부]
변경 전: 클라이언트가 넘긴 roomId를 그대로 Redis 키로 사용
변경 후: DB에서 is_active=true인 방을 조회해 chatRoomId를 Redis 키로 사용
```

**수정이 필요한 파일 6곳:**

| 파일 | 변경 내용 |
|------|-----------|
| `ChatMessageController.java` | `@MessageMapping("/chat/{roomId}")` → `/chat` |
| `ChatMessageController.java` | roomId 파라미터 제거, 내부에서 활성 방 조회 |
| `ChatMessageService.java` | `isRateLimited`, `save`, `getHistory` 시그니처 유지 (내부 키는 동일) |
| `RedisSubscriber.java` | 채널 파싱 로직 단순화 (`chat:room` 고정) |
| `ChatEventListener.java` | destination에서 roomId 추출 로직 제거 |
| `SessionRegistry.java` | `Map<Long, Set<String>>` → `Set<String>` |

---

## 4. 논의 포인트

### 제거하는 게 맞다는 근거

- 클라이언트가 roomId를 직접 입력받아 요청하면 **존재하지 않는 roomId로 메시지 발행이 가능**하다 (현재 코드에서 DB 검증 없음)
- 1인 인플루언서 구조에서 방이 1개인데 ID를 파라미터로 노출할 이유가 없다.
- 코드가 단순해진다.

### 유지하는 게 맞다는 근거

- 나중에 이벤트를 동시에 여러 개 열 수도 있다 (확장성)
- `chat_rooms` 테이블이 이미 PK(`chat_room_id`) 기반으로 설계되어 있어 구조와 일치한다.
- 현재는 DB 검증이 없지만, 추후 `roomId` 유효성 검사를 붙일 수 있다.

### 절충안: roomId는 유지하되 클라이언트에게 숨기기

클라이언트는 roomId 없이 연결하고, 서버가 활성 방 조회 후 roomId를 내부적으로만 쓰는 방식.
엔드포인트는 단순해지고 확장성도 유지된다.

```
클라이언트: /app/chat 으로 발행
서버:       DB에서 활성 방 조회 → chatRoom.getChatRoomId() → Redis 키로 사용
```

이 경우 `ChatRoomRepository`에 메서드 1개만 추가하면 된다:
```java
Optional<ChatRoom> findByIsActiveTrue();
```

---

## 5. 현재 코드에서 미구현된 부분 (어떤 방향이든 필요)

roomId 제거 여부와 무관하게, 현재 코드에는 아래 검증이 빠져 있다.

| 항목 | 현재 상태 | 필요한 처리 |
|------|-----------|-------------|
| 채팅방 활성 여부 검증 | ❌ 없음 | 메시지 수신 시 `is_active=true`인지 확인 |
| 존재하지 않는 roomId 방어 | ❌ 없음 | 잘못된 roomId로 Redis에 고아 데이터 생성 가능 |
| 채팅방 종료 시 브로드캐스트 | `ROOM_CLOSED` 응답 타입은 있으나 트리거 없음 | REST API 또는 이벤트로 종료 처리 |
