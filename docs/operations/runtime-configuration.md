# 런타임 환경변수

애플리케이션 실행에 필요한 환경변수의 정본이다. 시크릿 값은 저장소, 로그, Issue, PR에 남기지 않는다.

## 공통

| 변수 | 적용 환경 | 필수 | 설명 |
| --- | --- | --- | --- |
| `JWT_SECRET` | local, dev, 운영 | 예 | Access JWT HS256 서명·검증용 값. Base64 decode 결과가 32바이트 이상이어야 한다. |
| `FRONTEND_CALLBACK_URL` | 운영 | 예 | OAuth Login Code를 전달할 Frontend callback URL. |
| `REFRESH_COOKIE_SECURE` | 운영 | 예 | HTTPS로 서비스하는 운영 환경에서는 반드시 `true`를 주입해 Refresh Cookie에 `Secure` 속성을 설정한다. 누락하면 기동에 실패한다. local 프로필은 HTTP를 위해 `false`를 명시한다. |

`JWT_SECRET`은 예를 들어 `openssl rand -base64 32`으로 생성한다. local·dev 설정에 기본값은 없으며,
누락하면 애플리케이션이 기동하지 않는다. 테스트는 `src/test/resources/application-test.yml`의 전용 키를
사용하고, 이 값은 런타임 환경변수가 아니다.

## 개발 배포 프로필

`SPRING_PROFILES_ACTIVE=dev`에서는 다음 값도 필요하다.

| 변수 | 필수 | 설명 |
| --- | --- | --- |
| `DB_HOST` | 예 | MySQL 호스트 |
| `DB_USERNAME` | 예 | MySQL 사용자 이름 |
| `DB_PASSWORD` | 예 | MySQL 비밀번호 |
| `REDIS_HOST` | 아니오 | Redis 호스트. 미설정 시 `redis` |
| `DOCS_USERNAME` | 예 | Swagger Basic Auth 사용자 이름 |
| `DOCS_PASSWORD` | 예 | Swagger Basic Auth 비밀번호 |

## OAuth 프로필

OAuth 로그인을 사용할 때는 `SPRING_PROFILES_ACTIVE`에 `oauth`를 추가하고 다음 Provider credential을
주입한다.

| 변수 | 필수 | 설명 |
| --- | --- | --- |
| `OAUTH_GOOGLE_CLIENT_ID` | 예 | Google OAuth Client ID |
| `OAUTH_GOOGLE_CLIENT_SECRET` | 예 | Google OAuth Client Secret |
| `OAUTH_KAKAO_CLIENT_ID` | 예 | Kakao REST API 키 |
| `OAUTH_KAKAO_CLIENT_SECRET` | 예 | Kakao Client Secret |

OAuth 사용 예시는 `SPRING_PROFILES_ACTIVE=local,oauth`다. Provider scope와 endpoint 설정은
`application-oauth.yml`, 인증 흐름은 `docs/domains/auth/README.md`를 따른다.
