# 관리자 백오피스 통합 검증

이 문서는 관리자 Web이 Backend만으로 로그인·토큰 갱신·운영 API 호출을 연결할 수 있는지 검증하는 범위를 정의한다.
외부 API 계약은 [Auth API](api.md), 운영 API의 상세 계약은 각 도메인 API 문서가 정본이다.

## 자동 검증 범위

`AdminBackofficeFlowIntegrationTest`는 실제 Spring Security·JWT·Redis Refresh Token·JPA 관리자 계정을 사용한다.

1. 테스트 전용 ADMIN Member와 `AdminAccount`를 만들고 `POST /api/auth/admin/login`을 호출한다.
2. 발급 Access JWT로 `GET /api/me`의 `role=ADMIN`, `GET /api/admin/events`,
   `GET /api/admin/redraw-requests` 성공을 확인한다.
3. `admin_refresh_token`으로 `POST /api/admin/auth/refresh`를 호출해 새 JWT의 ADMIN 역할을 확인한다.
4. `POST /api/admin/auth/logout` 뒤 같은 Refresh Cookie가 `INVALID_REFRESH_TOKEN`으로 거절되는지 확인한다.
5. 사용자 Web은 `/api/auth/refresh`, 관리자 Web은 `/api/auth/admin/login`의 credential CORS 사전 요청을
   허용하고, 반대 Web Origin과 등록되지 않은 Origin은 차단하는지 확인한다.

테스트 계정의 login ID는 실행마다 UUID로 만들고, 비밀번호·Access JWT·Refresh Token은 로그나 문서에 남기지 않는다.

## 기존 도메인 검증과 경계

Event 승인·수동 마감·CLOSING/CLOSED 조회, Snapshot, INITIAL Drawing, Winner 상태 변경,
Redraw 생성·검토·실행의 상태 전이와 동시성은 각 도메인의 controller·application 통합 테스트가 검증한다.
이 문서는 해당 테스트를 중복하지 않고, 백오피스가 사용하는 인증과 목록 진입점이 함께 연결되는지를 검증한다.

## 프런트엔드 연동 조건

- 관리자 로그인은 `POST /api/auth/admin/login`에 `loginId`, `password`를 보내며, 응답의 Access JWT는
  `Authorization: Bearer`로 운영 API에 전달한다.
- 브라우저는 `credentials: "include"`로 관리자 Refresh·Logout을 호출하고,
  `admin_refresh_token` Cookie를 JavaScript에서 읽지 않는다.
- 관리자 인증 요청 Origin은 `cking.auth.refresh.admin-allowed-origins`, 일반 관리자 API Origin은
  `cking.cors.allowed-origins`에 각각 등록해야 한다. 개발 환경의 관리자 Web은
  `https://dev-admin.cking.co.kr`을 사용한다.
- 사용자 Web `https://dev.cking.co.kr`은 사용자 Refresh Cookie 흐름만 사용한다. 서로 다른 Origin 또는
  허용 목록 밖 Origin의 credential 요청은 CORS에서 차단된다.
