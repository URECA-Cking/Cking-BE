# Auth 도메인

Auth 도메인은 Cking Member의 외부 신원 확인과 Cking API 인증 수단 발급의 경계를 정한다.
이 문서는 장기 목표 설계의 정본이다. **현재 저장소에는 Spring Security, OAuth2 Client,
JWT 인증이 아직 구현되어 있지 않다.** 현재 외부 API의 호출자 `userId` 계약은
[공통 API 규약](../../common/api.md)을 따른다.

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
- 외부 identity key는 email이 아니다. Google은 `sub`, Kakao는 `id`를 `providerUserId`로 쓴다.
- `(provider, providerUserId)`가 외부 Identity의 고유 식별자다. 같은 email이어도 Google과 Kakao
  계정을 자동 병합하지 않는다.

## 목표 인증 흐름

```text
Google / Kakao → Spring Security OAuth2 Client → Provider 사용자 정보
    → OAuthUserInfoResolver (Google Mapper / Kakao Mapper) → OAuthLoginService
    → OAuthAccount / Member → memberId → OAuth2LoginSuccessHandler
    → 1회용 Login Code → Redis → Frontend Callback → POST /api/auth/token
    → Access Token + Refresh Token → Access JWT → Spring Security Resource Server
    → SecurityContext → @CurrentMemberId → Controller → 기존 Application / Domain
```

Provider별 응답 형식은 Mapper가 공통 `OAuthUserInfo`(`provider`, `providerUserId`, `email`,
`name`)로 정규화한다. `OAuthLoginService`는 Google/Kakao별 조건문 없이 이 값을 받아
OAuthAccount를 조회·연결한다.

## 책임 경계

### OAuth 사용자 식별과 Member 연결

OAuth 사용자 정보 정규화, `OAuthUserInfo` 생성, `(provider, providerUserId)` 기준 OAuthAccount
조회, 최초 로그인 시 `Member(USER)`·OAuthAccount 생성, 기존 사용자의 연결 `memberId` 반환을 담당한다.
최종 결과는 항상 `memberId`다.

### 로그인 완료와 Cking 인증 수단 발급

입력은 `memberId`다. OAuth 로그인 성공 처리, Login Code 생성·Redis 저장·1회 소비, Access JWT와
Refresh Token 발급·회전, Logout, JWT 검증, 인증된 Member ID의 Controller 제공을 담당한다.
Google/Kakao 매핑이나 Member 연결 정책을 직접 처리하지 않는다.

## 토큰 정책

### Login Code

Login Code는 OAuth 로그인 결과를 Frontend로 안전하게 전달하는 1회용 교환 코드이며 인증 토큰이 아니다.

- TTL은 60초, 한 번만 사용한다.
- `SecureRandom`으로 생성하고 Redis에는 원문 대신 Hash를 key로 저장한다.
- OAuth 성공 후 `memberId`를 담아 Frontend로 redirect하고, Frontend는
  `POST /api/auth/token`으로 교환한다. JWT를 redirect URL query parameter에 넣지 않는다.

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

### Refresh Token

Refresh Token은 JWT가 아닌 opaque random token이다.

- TTL은 14일이며 Redis에는 Hash 기반 key로 저장한다. Refresh Cookie는 `refresh_token`,
  `Path=/api/auth`, host-only, `HttpOnly`, `Secure`, `SameSite=None`을 사용한다. 세부 외부
  계약은 [Auth API](api.md#refresh-cookie-계약)를 따른다.
- Refresh 성공 시 기존 토큰을 폐기하고 새 토큰을 발급한다. 폐기된 토큰은 재사용할 수 없다.
- Logout은 Redis의 Refresh Token을 폐기하고 Refresh Cookie를 만료시킨다.

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
