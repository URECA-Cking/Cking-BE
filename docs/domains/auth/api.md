# Auth API

이 문서는 Auth 외부 API의 **Sprint 2 목표 계약**이다. 현재 저장소에는 이 endpoint와
인증 구현이 없으며, 실제 인증 전환 전의 기존 API `userId` 계약은
[공통 API 규약](../../common/api.md)을 그대로 따른다.

모든 구현 완료 후 응답은 공통 `ApiResponse` 봉투를 사용한다. 아래 JSON 예시는 `data` 값이다.

## OAuth 로그인 시작

### `GET /oauth2/authorization/google`

### `GET /oauth2/authorization/kakao`

- 권한: `PUBLIC`
- Spring Security OAuth2 Client의 기본 authorization endpoint를 사용해 각 Provider 인증 화면으로 redirect한다.
- Provider callback은 Spring Security OAuth2 Client가 처리한다. 별도 Controller API로 만들지 않는다.

성공하면 서버는 Frontend callback으로 Login Code만 전달한다.

```text
/oauth/callback?code=...
```

Access JWT와 Refresh Token을 redirect URL에 포함하지 않는다.

## Token 교환

### `POST /api/auth/token`

- 권한: `PUBLIC`
- Request Body의 `code`는 Login Code 원문이다.

```json
{ "code": "..." }
```

Login Code를 검증하고 원자적으로 한 번 소비한 뒤 `memberId`로 Access Token과 Refresh Token을
발급한다. 응답은 Access Token 정보만 제공하며 Refresh Token은 HttpOnly Cookie로 전달한다.

```json
{
  "accessToken": "...",
  "tokenType": "Bearer",
  "expiresIn": 1800
}
```

## Token 갱신

### `POST /api/auth/refresh`

- 권한: `PUBLIC`
- HttpOnly Cookie의 Refresh Token을 사용한다. Request Body는 없다.
- Redis 상태를 확인한 뒤 기존 Refresh Token을 폐기하고 새 Refresh Token과 Access Token을 발급한다.
- 응답 `data`와 Refresh Cookie의 형식은 [Token 교환](#token-교환)과 같다.

## Logout

### `POST /api/auth/logout`

- 권한: `PUBLIC`
- HttpOnly Cookie의 Refresh Token이 있으면 Redis에서 제거하고 Refresh Cookie를 만료한다.
- 이미 만료·폐기되었거나 Cookie가 없는 경우에도 클라이언트 Cookie 만료 처리를 수행한다.
- 성공 시 `200 OK`와 공통 성공 응답을 반환한다.

## 현재 사용자 조회

### `GET /api/me`

- 권한: `USER`, `ADMIN`
- Access JWT를 검증해 얻은 현재 `memberId`만 조회한다. 호출자 `userId` query parameter나 body는 받지 않는다.

```json
{
  "memberId": 1,
  "name": "홍길동",
  "email": "user@example.com",
  "role": "USER",
  "creator": false
}
```

`creator`는 JWT claim이 아니라 `creator.member_id` 존재 여부를 반영한다.

## 오류 계약

구현 시 오류 응답은 공통 `ApiResponse`와 `ErrorCode` 규칙을 따른다. 인증 전용 오류 코드는
Auth 도메인 ErrorCode로 정의하고, 공통 오류와 중복하지 않는다.

| HTTP | 상황 |
| --- | --- |
| 400 | Login Code 요청 형식이 올바르지 않음 |
| 401 | 인증 정보가 없거나 Access JWT가 잘못되었거나 만료됨 |
| 401 | Login Code가 유효하지 않거나 만료되었거나 이미 소비됨 |
| 401 | Refresh Token이 유효하지 않거나 만료·폐기됨 |
| 403 | 인증되었지만 endpoint 권한이 부족함 |

`POST /api/auth/token`의 Login Code 소비와 `POST /api/auth/refresh`의 Refresh Token rotation은
동시 요청에서도 각각 한 번만 성공하도록 원자적으로 처리한다.
