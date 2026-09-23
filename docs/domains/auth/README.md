# Auth 도메인

Auth 도메인은 Cking Member의 외부 신원 확인과 Cking API 인증 수단 발급의 경계를 정한다.
현재 저장소에는 Spring Security OAuth2 Client·Resource Server, OAuth 로그인 완료의 Login Code
발급·전달, Access JWT 발급·검증, REST 401/403 응답 처리가 구성되어 있다. 기존 업무 API의 호출자
`userId` 계약은 [공통 API 규약](../../common/api.md)을 계속 따른다.

## 현재 Security 기반

- Security 필터 체인은 모든 요청을 처리한다. Swagger UI와 OpenAPI JSON은 전용 SecurityFilterChain에서
  Basic Auth로 보호한다. 문서 계정 설정이 비어 있는 로컬·CI 환경에서는 Basic Auth를 적용하지 않는다.
- `/actuator/health`, `/actuator/info`는 ALB와 모니터링의 상태 확인을 위해 인증 없이 노출·허용한다.
  그 외 actuator 경로는 공개하지 않는다.
- Resource Server는 Bearer JWT의 서명·만료·Issuer·Claim을 검증한다. 기존 업무 API는 호출자 `userId`
  전환 전까지 현재 `permitAll` 규칙을 유지하며, JWT가 필요한 신규 API부터 `@CurrentMemberId`를 사용한다.
  업무 권한 검증을 Spring Security로 대체하지 않는다.
- CSRF는 현재 세션 기반 인증을 사용하지 않는 기존 API 호환을 위해 비활성화한다. CORS는
  `cking.cors.allowed-origins`와 `cking.cors.allow-credentials` 설정을 사용하며, credential 기본값은
  `false`다. Refresh Cookie를 실제 도입하는 작업에서 배포 구조에 맞는 허용 origin과 credential을 함께
  활성화한다.
- JWT 인증 실패는 `RestAuthenticationEntryPoint`가 공통 `UNAUTHORIZED`(401)로, 인증 후 인가 실패는
  `RestAccessDeniedHandler`가 `FORBIDDEN`(403)으로 응답한다.

## 지원 범위와 모델

- Google OAuth2와 Kakao OAuth2를 지원한다. 네이버 OAuth와 로컬 ID/PW 회원가입·로그인은 지원하지 않는다.
- OAuth 최초 로그인 시 Cking `Member`를 자동 생성한다.
- `Member.role`은 현재처럼 `USER`, `ADMIN`만 사용한다. Creator는 Member Role이 아니다.
  Creator 여부는 `creator.member_id` 존재로 판단하며, Creator 자격과 Event 소유권은 기존
  Application/Domain 정책이 판단한다.

### OAuthAccount

`OAuthAccount`는 외부 OAuth Identity와 Cking Member를 연결한다.

```text
(provider, providerUserId) → memberId
```

- 현재 프로젝트 스타일에 맞춰 Member와 JPA 연관관계를 강제하지 않고 `Long memberId`를 보관한다.
- `oauth_account`는 `oauthAccountId`, `memberId`, `provider`, `providerUserId`, `createdAt`을 저장하며,
  `memberId`는 Member FK다.
- 이 FK 때문에 정상 DB 상태에서는 OAuthAccount가 존재하지만 연결 Member가 없는 상태를 허용하지 않는다.
- 외부 identity key는 email이 아니다. Google은 `sub`, Kakao는 `id`를 `providerUserId`로 쓴다.
- `(provider, providerUserId)`가 외부 Identity의 고유 식별자다. 같은 email이어도 Google과 Kakao
  계정을 자동 병합하지 않는다.
- DB는 `UNIQUE(provider, provider_user_id)`로 이 고유성을 최종 보장한다.
- 최초 로그인은 OAuthAccount를 먼저 조회하고, 없으면 `Member`와 OAuthAccount 생성을 시도한다. 동시
  최초 로그인으로 OAuthAccount INSERT가 충돌하면 Transaction을 정리한 뒤 기존 OAuthAccount를 재조회해
  연결된 `memberId`를 반환한다. 단일·다중 인스턴스 모두 DB 제약을 최종 방어선으로 사용한다.

### OAuth 프로필 name 정규화

Provider의 `name`은 선택적 프로필 정보이지 외부 Identity가 아니다. Mapper는 Provider 응답을
`OAuthUserInfo.name`으로 정규화하고, `OAuthLoginService`는 최초 `Member` 생성 전에 다음 규칙을 적용한다.

- null·공백 name은 기본값 `사용자`로 바꾼다.
- 앞뒤 공백을 제거한 name이 50자를 넘으면 Unicode code point 경계를 깨지 않도록 앞 50자로 제한한다.
- 정규화 결과를 `Member.name`에 저장한다. 기본값은 고유할 필요가 없으며, email이나
  `providerUserId`를 name fallback 또는 Identity 판단에 사용하지 않는다.

## 목표 인증 흐름

```text
Google / Kakao → Spring Security OAuth2 Client → Provider 사용자 정보
    → OAuthMemberLoginService → OAuthUserInfoResolver (Google Mapper / Kakao Mapper)
    → OAuthLoginService
    → OAuthAccount / Member → memberId → OAuth2LoginSuccessHandler
    → 1회용 Login Code → Redis → Frontend Callback → POST /api/auth/token
    → Access JWT → Spring Security Resource Server
    → SecurityContext → @CurrentMemberId → Controller → 기존 Application / Domain
```

Provider별 응답 형식은 Mapper가 공통 `OAuthUserInfo`(`provider`, `providerUserId`, `email`,
`name`)로 정규화한다. `OAuthLoginService`는 Google/Kakao별 조건문 없이 이 값을 받아
OAuthAccount를 조회·연결한다.

### OAuth Client 설정

- OAuth Provider credential은 저장소에 두지 않고 `oauth` 프로필의 환경변수로만 주입한다. 필요한 변수와
  프로필 조합은 [런타임 환경변수](../../operations/runtime-configuration.md#oauth-프로필)를 따른다.
- Google은 Spring Security의 기본 Provider 설정과 `OAUTH_GOOGLE_CLIENT_ID`,
  `OAUTH_GOOGLE_CLIENT_SECRET`, `profile,email` scope를 사용한다.
- Kakao의 `OAUTH_KAKAO_CLIENT_ID`는 REST API 키이며, `OAUTH_KAKAO_CLIENT_SECRET`은 Kakao Client
  Secret이다. Authorization·Token·UserInfo URI는 Kakao REST API 기준으로 설정하고
  `profile_nickname,account_email` scope를 요청한다.
- Mapper·Resolver는 Provider 응답 정규화와 `memberId` 반환 경계까지만 담당하며, Login Code 발급과
  Access JWT 교환은 별도 Auth Service가 맡는다.

## 책임 경계

### OAuth 사용자 식별과 Member 연결

OAuth 사용자 정보 정규화, `OAuthUserInfo` 생성, `(provider, providerUserId)` 기준 OAuthAccount
조회, 최초 로그인 시 `Member(USER)`·OAuthAccount 생성, 기존 사용자의 연결 `memberId` 반환을 담당한다.
최초 로그인 경쟁 시에는 OAuthAccount의 DB 고유 제약 충돌을 기존 계정 재조회로 복구한다. 최종 결과는
항상 `memberId`다.

### 로그인 완료와 Cking 인증 수단 발급

입력은 `memberId`다. OAuth 로그인 성공 처리, Login Code 생성·Redis 저장·1회 소비, Access JWT 발급·검증,
인증된 Member ID의 Controller 제공을 담당한다.
Google/Kakao 매핑이나 Member 연결 정책을 직접 처리하지 않는다.

## 토큰 정책

### Login Code

Login Code는 OAuth 로그인 결과를 Frontend로 안전하게 전달하는 1회용 교환 코드이며 인증 토큰이 아니다.

- TTL은 60초, 한 번만 사용한다.
- `SecureRandom`으로 생성하고 Redis에는 원문 대신 Hash를 key로 저장한다.
- OAuth 성공 후 `memberId`를 담아 Frontend로 redirect하고, Frontend는
  `POST /api/auth/token`으로 교환한다. JWT를 redirect URL query parameter에 넣지 않는다.
- Redis key는 `auth:login-code:<SHA-256(code)>`, value는 `memberId`다. Lua의 `GET`과 `DEL`을
  한 원자 연산으로 실행해 동시 교환도 하나만 성공시킨다.
- Frontend callback 주소는 `cking.auth.frontend-callback-url`로 설정한다. 환경별 값과 운영의
  `FRONTEND_CALLBACK_URL` 필수 조건은 [런타임 환경변수](../../operations/runtime-configuration.md#공통)를 따른다.

### Access Token

Access Token은 Cking API 인증용 JWT다.

```text
TTL: 30분
iss: cking
sub: memberId
role: USER | ADMIN
iat, exp
```

Creator 여부는 JWT claim에 넣지 않는다.

### Refresh Token (후속 구현 범위)

Refresh Token은 아직 발급·회전·Cookie·갱신·Logout endpoint를 구현하지 않았다. 다음 계약은 후속 인증
작업을 위한 보존 규칙이며, 구현 전까지 [Auth API](api.md)에 `POST /api/auth/refresh` 또는
`POST /api/auth/logout`을 노출하지 않는다.

- Refresh Token은 JWT가 아닌 opaque random token이며 TTL은 14일이다.
- Redis에는 원문 token을 key나 value로 저장하지 않고 `SHA-256(token)` 기반 key로만 저장·조회·삭제한다.
- Refresh Cookie 이름은 `refresh_token`이며 `HttpOnly`, `Path=/api/auth`, `Domain` 미지정(host-only)을
  사용한다. 운영 환경에서는 `Secure=true`를 사용한다.
- `SameSite`는 배포 구조에 따라 정한다. same-site 배포는 `Lax`, cross-site Cookie가 필요하면
  `None`과 `Secure=true`를 함께 사용한다.
- Refresh 성공 시 기존 token을 원자적으로 한 번 소비하고 새 token을 저장·발급하는 rotation을 수행한다.
  소비된 token은 재사용할 수 없다.
- Logout은 Redis의 Refresh Token을 삭제하고 Refresh Cookie를 만료시킨다.
- Cookie 기반 Refresh·Logout 요청은 설정된 허용 origin과 일치하는 `Origin`만 허용해 CSRF를 방어한다.

Login Code와 Refresh Token의 만료·소비·폐기 같은 내부 상태는 외부 API에서 세분화하지 않는다.

| 대상 | 외부 오류 코드 | 통합하는 상태 |
| --- | --- | --- |
| Login Code | `INVALID_LOGIN_CODE` | 만료, 소비됨, 잘못된 값 |
| Refresh Token | `INVALID_REFRESH_TOKEN` | 만료, 폐기, 재사용, rotation 후 사용, 잘못된 값 |

세부 원인은 서버 로그와 모니터링에서만 구분한다. 외부 오류 구분을 위해 Redis에 tombstone 또는 meta
상태를 별도로 유지하지 않는다.

Login Code의 만료·소비·잘못된 값은 외부에서 모두 `INVALID_LOGIN_CODE`로 통합한다. 세부 원인은
서버 로그와 모니터링에서만 구분한다.

## 인증과 업무 권한

Spring Security는 인증 여부, JWT 검증, coarse-grained 접근 제어와 ADMIN endpoint의 1차 접근 제어를
담당한다. Application/Domain은 Member 존재, ADMIN 업무 권한, Creator 여부, Event 소유권,
상태 전이와 그 밖의 업무 규칙을 검증한다. Security 도입만으로 기존 `validateAdmin()` 같은
업무 권한 검증을 제거하지 않는다.

OAuth/Security/JWT 기술은 인증 경계에서 끝낸다.

```text
security / presentation → application → domain
```

Application/Domain은 `Authentication`, `SecurityContext`, `Jwt`, `OAuth2User`, HTTP
Request/Response에 직접 의존하지 않는다. Controller가 인증된 `memberId`를 꺼내 기존
Application/Service에 전달한다.

## 호출자 userId 전환

인증 전환 후 호출자 자신을 뜻하는 외부 Request `userId`는 제거한다.

```text
현재: Client userId → Controller → Application
목표: JWT → Spring Security → authenticated memberId → Controller → Application
```

다만 Entity의 `memberId`/`userId`, Stream payload·Snapshot의 `userId`, Winner·Entry 등의
사용자 식별자, `requestedBy`·`reviewedBy`·`createdBy`와 기타 도메인 데이터는 유지한다.
Application/Service 파라미터의 `userId`, `memberId`, `actorId`도 업무 수행 주체를 뜻하면 유지한다.
