# 공통 API 규약

## 적용 범위

외부 REST API에 적용한다. 내부 Service 호출은 API 인덱스와 해당 도메인 문서에서 정의한다.

## 응답 형식

서버 응답은 아래 공통 봉투를 사용한다.

```json
{
  "code": "SUCCESS",
  "data": {},
  "message": null
}
```

- 성공은 `code`가 `SUCCESS`다.
- 실패는 `data`가 `null`이며, `message`에 사용자에게 표시할 메시지를 담는다.
- HTTP 상태와 `code`를 함께 해석한다.

## 사용자 식별

- GET·DELETE 요청은 query parameter `userId`를 사용한다.
- POST·PATCH 요청은 body의 `userId`를 사용한다.
- `/api/me/**` 경로도 `userId`를 요청에 포함한다.

## 멱등성

- 멱등성이 필요한 명령 API는 API 상세에 명시된 키(`requestId` 또는 `idempotencyKey`)를 사용한다.
- 키의 위치, 재요청 결과, 상태 기반 중복 차단 규칙은 해당 도메인 API 문서가 정한다.

## 오류 코드

- 오류 코드의 정본은 서버의 `ErrorCode` enum이다.
- 전 도메인 공통 오류는 `CommonErrorCode`에 둔다.
- 업무 의미가 있는 오류는 해당 도메인 `*ErrorCode` enum에 둔다.
- 각 API 문서는 그 API가 반환할 수 있는 오류 코드만 적는다.

## 공통 API

### GET /api/users

- 권한: PUBLIC
- 역할: 가상 사용자 목록을 조회한다.

```json
{
  "code": "SUCCESS",
  "data": {
    "items": [{ "userId": "user-001", "name": "홍길동" }]
  },
  "message": null
}
```

### POST /api/demo/users/select

- 역할: 가상 사용자를 선택한다. 선택 결과는 클라이언트가 관리한다.

```json
{ "userId": "user-001" }
```

```json
{
  "code": "SUCCESS",
  "data": { "userId": "user-001", "name": "홍길동", "selected": true },
  "message": null
}
```

### GET /api/creators

- 권한: PUBLIC
- 역할: Creator 목록을 조회한다.

### GET /api/creators/{creatorId}

- 권한: PUBLIC
- 역할: Creator 상세를 조회한다.
