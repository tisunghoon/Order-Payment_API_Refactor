# WebSocket 프로덕션 배포 전 작업 체크리스트

> 작성일: 2026-04-15
> 대상 브랜치: `47-feat-websocket-메시지-발행-send_message`

현재 WebSocket 채팅 기능은 **테스트 편의를 위해 인증을 비활성화한 상태**로 구현되어 있습니다.
Sprint 4에서 배포 전 아래 작업을 반드시 수행해야 합니다.

---

## 작업 목록

### 1. `/ws` 엔드포인트에 JWT Handshake 인터셉터 활성화

**파일:** `backend/src/main/java/com/myfave/api/global/config/WebSocketConfig.java:29-31`

**현재 상태 (주석 처리됨):**
```java
registry.addEndpoint("/ws")
        // TODO: 테스트 후 인터셉터 활성화
        // .addInterceptors(jwtHandshakeInterceptor)
        .setAllowedOriginPatterns("*")
        .withSockJS();
```

**배포 시 변경:**
```java
registry.addEndpoint("/ws")
        .addInterceptors(jwtHandshakeInterceptor)
        .setAllowedOriginPatterns("*")
        .withSockJS();
```

**역할:** WebSocket 핸드셰이크 시 URL 쿼리 파라미터 `?token=<JWT>`를 검증하고,
성공 시 `sessionAttributes`에 `userId`를 저장.

---

### 2. 테스트 전용 `/ws-test` 엔드포인트 제거

**파일:** `backend/src/main/java/com/myfave/api/global/config/WebSocketConfig.java:34-36`

**현재 상태 (배포 전 제거 대상):**
```java
// TODO: 테스트 전용 — 배포 전 제거
registry.addEndpoint("/ws-test")
        .setAllowedOriginPatterns("*");
```

**배포 시 변경:** 위 3줄 전체 삭제.

**이유:** `/ws-test`는 SockJS 없이, 인증도 없이 WebSocket에 연결 가능한 엔드포인트.
프로덕션에 남아있으면 인증 우회 경로가 됨.

---

### 3. STOMP 채널 인터셉터 활성화

**파일:** `backend/src/main/java/com/myfave/api/global/config/WebSocketConfig.java:40-43`

**현재 상태 (주석 처리됨):**
```java
@Override
public void configureClientInboundChannel(ChannelRegistration registration) {
    // TODO: 테스트 후 인터셉터 활성화
    // registration.interceptors(jwtChannelInterceptor);
}
```

**배포 시 변경:**
```java
@Override
public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(jwtChannelInterceptor);
}
```

**역할:** STOMP CONNECT 프레임의 `Authorization: Bearer <JWT>` 헤더를 검증.
핸드셰이크 인터셉터(#1)와 이중 방어선을 구성함.

---

### 4. ChatMessageController의 userId fallback 제거 (2곳)

**파일:** `backend/src/main/java/com/myfave/api/domain/chat/controller/ChatMessageController.java`

#### 4-1. `sendMessage()` 메서드 (line 42-43)

**현재 상태:**
```java
Long userId = (Long) headerAccessor.getSessionAttributes().get("userId");
// TODO: 테스트 후 제거 — JWT 인터셉터 활성화 시 아래 fallback 삭제
if (userId == null) userId = 1L;
```

**배포 시 변경:**
```java
Long userId = (Long) headerAccessor.getSessionAttributes().get("userId");
if (userId == null) {
    log.warn("인증되지 않은 WebSocket 메시지 수신 — 연결 차단");
    return;
}
```

#### 4-2. `handleException()` 메서드 (line 79-80)

**현재 상태:**
```java
Long userId = (Long) headerAccessor.getSessionAttributes().get("userId");
if (userId == null) userId = 1L;
```

**배포 시 변경:**
```java
Long userId = (Long) headerAccessor.getSessionAttributes().get("userId");
if (userId == null) return;
```

**이유:** 인터셉터(#1, #3)가 활성화되면 `userId`가 null인 경우는 핸드셰이크 단계에서
이미 차단되어야 정상. fallback이 남아있으면 인터셉터가 실패한 경우에도
`userId=1` 사용자로 메시지가 처리되는 인증 우회 취약점이 됨.

---

## 적용 순서

```
1번 (핸드셰이크 인터셉터 활성화)
    ↓
3번 (채널 인터셉터 활성화)
    ↓
4번 (fallback 제거)
    ↓
2번 (테스트 엔드포인트 제거)
```

인터셉터를 먼저 활성화한 뒤 fallback을 제거해야 정상 동작을 확인하면서 안전하게 전환 가능.

---

## 관련 파일 구조

```
global/config/
├── WebSocketConfig.java         ← 1, 2, 3번 작업 대상
├── JwtHandshakeInterceptor.java ← 핸드셰이크 시 URL 쿼리 token 파라미터 검증
└── JwtChannelInterceptor.java   ← STOMP CONNECT 헤더 Authorization 검증

domain/chat/controller/
└── ChatMessageController.java   ← 4번 작업 대상
```

---

## 프론트엔드 연동 방식 (참고)

인터셉터 활성화 후 프론트엔드는 아래 방식으로 연결해야 함:

**핸드셰이크 토큰 전달 (JwtHandshakeInterceptor 대상):**
```
ws://host/ws?token=<JWT>
```

**STOMP CONNECT 헤더 전달 (JwtChannelInterceptor 대상):**
```javascript
stompClient.connect({ Authorization: `Bearer ${token}` }, callback);
```
