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

### 현재: 인증 시스템 미도입 단계

- 외부 요청의 `userId`는 호출자 자신을 식별하는 현재 API 계약이다.
- GET·DELETE 요청은 query parameter `userId`를 사용한다.
- POST·PATCH 요청은 body의 `userId`를 사용한다.
- `/api/me/**` 경로도 `userId`를 요청에 포함한다.
- `POST /api/demo/users/select`의 `userId`는 클라이언트가 선택하는 가상 사용자를 뜻하며, 선택 결과는 클라이언트가 관리한다.

Controller는 요청에서 호출자 `userId`를 추출해 Application/Service에 비즈니스 수행 주체 ID로 전달한다. Application/Service는 presentation Request DTO에 직접 의존하지 않는다.

### 향후: 인증 시스템 도입 단계

호출자 자신을 나타내는 Request의 `userId`는 제거한다. Controller는 인증된 사용자 정보에서 사용자 ID를 추출해 기존 Application/Service의 actor ID로 전달한다.

- 인증 관련 객체와 HTTP Request/Response는 Controller와 인증 계층 경계에서만 사용한다.
- Application/Service/Domain은 인증 기술에 직접 의존하지 않는다.
- Application Command 또는 Service 파라미터의 `userId`·`actorId`는 비즈니스 수행 주체를 의미하므로 유지할 수 있다.
- 인증 계층은 신원 확인을 담당한다. Service/Domain은 소유권, 역할, 상태 등 업무 권한을 검증한다.

### 호출자 식별과 리소스·업무 데이터 식별

- 호출자 `userId`는 요청을 수행하는 주체를 뜻한다.
- `eventId`, `winnerId`, `notificationId`, `creatorId`와 같은 ID는 조회·변경 대상 리소스를 식별한다.
- 특정 대상의 `memberId`, 응답의 사용자 ID, Entity·Redis Stream·Scheduler·Hash 입력에 사용되는 `userId` 또는 `memberId`는 업무 데이터다. 인증 도입만을 이유로 변경하지 않는다.
- `userId`를 포함한 DB `BIGINT` PK/FK 대응 식별자는 Java `Long`, JSON number 타입을 사용한다.
- 문서 예시의 `user-001` 같은 문자열은 식별자 종류를 설명하기 위한 표기일 뿐이며, 실제 요청에는 number를 사용한다.

## 페이지네이션

일반 목록과 관리자 목록은 Page 방식을 사용한다.

- `page`는 0부터 시작한다.
- `size`의 기본값은 20, 최댓값은 100이다.
- 목록 API는 문서에 명시한 기본 정렬과 PK 기반 tie-breaker를 사용한다.

응답 `data`는 다음 형식을 사용한다.

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0,
  "hasNext": false
}
```

## 멱등성

- 멱등성이 필요한 명령 API는 API 상세에 명시된 키(`requestId` 또는 `idempotencyKey`)를 사용한다.
- 키의 위치, 재요청 결과, 상태 기반 중복 차단 규칙은 해당 도메인 API 문서가 정한다.

## 오류 코드

- 오류 코드의 정본은 서버의 `ErrorCode` enum이다.
- 전 도메인 공통 오류는 `CommonErrorCode`에 둔다.
- 업무 의미가 있는 오류는 해당 도메인 `*ErrorCode` enum에 둔다.
- 각 API 문서는 그 API가 반환할 수 있는 오류 코드만 적는다.

인증 도입 후 `VALIDATION_FAILED`, `UNAUTHORIZED`, `FORBIDDEN`, `SYSTEM_ERROR`도 전 도메인 공통
오류로서 `CommonErrorCode`에 둔다. `INVALID_LOGIN_CODE`, `INVALID_REFRESH_TOKEN`처럼 Auth 업무
의미가 있는 오류만 `AuthErrorCode`에 둔다.

### 인증 도입 후 오류 기준

- Access Token 없음·만료·변조·형식 오류는 공통 `UNAUTHORIZED`(401)로 통합한다.
- Login Code의 만료·소비·잘못된 값은 `INVALID_LOGIN_CODE`로, Refresh Token의 만료·폐기·재사용·
  잘못된 값은 `INVALID_REFRESH_TOKEN`으로 각각 통합한다.
- token의 세부 실패 원인은 외부 오류 코드로 노출하지 않고 서버 로그·모니터링에서만 구분한다.
- 인증된 호출자의 업무 권한 부족은 `FORBIDDEN`(403), 요청 형식 오류는 `VALIDATION_FAILED`, 예상하지
  못한 오류는 `SYSTEM_ERROR`을 사용한다.

## Swagger UI 문서화

- Swagger UI는 `/swagger-ui/index.html`, OpenAPI JSON은 `/v3/api-docs`에서 제공한다.
- 외부 API의 정본은 [전체 API 인덱스](../api-index.md), 이 문서, 도메인별 API 문서다. Swagger UI는 이 계약을 보기 쉽게 표시하는 보조 문서이며 계약을 대체하지 않는다.
- 문서화 대상 Controller 클래스에는 도메인 단위의 `@Tag(name, description)`을, 해당 외부 endpoint에는 동작을 요약하는 `@Operation(summary, description)`을 추가한다.
- `summary`는 API가 수행하는 동작을 짧게 쓰고, `description`에는 권한, 멱등성, 비동기 처리, 페이지네이션처럼 호출자가 알아야 하는 계약만 적는다. DTO 필드·공통 응답 봉투·오류 코드를 중복해서 나열하지 않는다.
- HTTP 상태별 `@ApiResponse`가 필요한 경우 프로젝트의 `kr.co.cking.common.response.ApiResponse`와 이름이 같으므로 `io.swagger.v3.oas.annotations.responses.ApiResponse`의 완전 수식명을 사용한다.

## 공통 API

### GET /api/users

- 권한: PUBLIC
- 역할: 가상 사용자 목록을 조회한다.

```json
{
  "code": "SUCCESS",
  "data": {
    "items": [{ "userId": 1, "name": "홍길동" }]
  },
  "message": null
}
```

### POST /api/demo/users/select

- 역할: 가상 사용자를 선택한다. 선택 결과는 클라이언트가 관리한다.

```json
{ "userId": 1 }
```

```json
{
  "code": "SUCCESS",
  "data": { "userId": 1, "name": "홍길동", "selected": true },
  "message": null
}
```

### GET /api/creators

- 권한: PUBLIC
- 역할: Creator 목록을 조회한다.

### GET /api/creators/{creatorId}

- 권한: PUBLIC
- 역할: Creator 상세를 조회한다.
