# Member 공개 닉네임 정책

`member.name`은 OAuth Provider가 전달한 원본 이름으로, 계정 연결·관리용 정보다. `member.nickname`은 새 공개
활동명이다. 기존 회원은 OAuth 이름을 자동 이관하지 않으며, `nickname`이 `null`이면 아직 공개 프로필을 설정하지
않은 상태다.

## 형식과 중복

- 입력은 NFC 정규화 후 `^[가-힣A-Za-z0-9_-]{1,20}$`를 만족해야 한다.
- 완성형 한글, ASCII 영문·숫자, `_`, `-`만 허용한다. 공백, 한글 자모, 전각 문자는 허용하지 않는다.
- 중복 비교 키는 NFC 문자열을 `Locale.ROOT` 소문자로 바꾼 `nickname_normalized`다. DB UNIQUE가 최종
  방어선이다.
- 금칙어·예약어는 현재 차단하지 않는다. `MemberNicknameContentPolicy`가 후속 정책의 확장 지점이다.

## 변경과 복귀

- 최초 설정은 즉시 가능하며 `nickname_changed_at`을 만들지 않는다.
- 최초 설정 뒤 실제 이름을 바꾸면 그 시각부터 14일 동안 다시 바꿀 수 없다. 현재 이름과 같은 요청은 성공
  no-op이다.
- 변경하며 버린 이름은 `member_nickname_reservation`에 14일 동안 예약한다. 예약이 살아 있는 즉시 이전 이름으로
  복귀할 수 있고, 복귀는 `nickname_changed_at`을 갱신하지 않는다.
- 복귀하면 새로 버린 현재 이름을 예약하고, 이전 예약은 해제한다. 만료 예약은 이름을 조회·사용하려 할 때 삭제하며
  별도 정리 배치는 두지 않는다.

닉네임 변경 Command는 현재 `MemberNicknameService`에 있다. 외부 프로필 수정 API는 프로필 사진 작업과 함께
추가한다. `GET /api/me`의 현재 닉네임·다음 변경 가능 시각·즉시 복귀할 이름은 그 UI가 사용한다.

## 배포 순서와 트랜잭션

- 이번 변경은 정책·이력·조회 상태만 준비한다. 기존 댓글·당첨자 등 공개 응답은 다음 프로필 수정 API와 화면이 배포된
  뒤 닉네임으로 전환한다. 따라서 기존 회원을 `닉네임 미설정`으로 보이게 하거나 OAuth 이름을 자동 이관하지 않는다.
- `PROFILE_SETUP_REQUIRED`는 다음 작업에서 댓글 작성, Creator 신청, 이벤트 응모를 막는 데 사용한다. 팔로우와 기존
  미션 참여는 계속 허용한다.
- `MemberNicknameTransactionalService`는 기본 전파(`REQUIRED`)로 호출자 트랜잭션에 참여한다. 프로필 사진과
  닉네임을 함께 수정하는 API는 이 서비스를 같은 DB 트랜잭션에서 호출하고, Object Storage 업로드가 선행됐는데 DB
  저장이 실패하면 객체를 보상 삭제한다. 독립 명령 진입점은 고유성 충돌을 `NICKNAME_ALREADY_TAKEN`으로 변환한다.

## OAuth와 공개 응답

OAuth 재로그인은 기존 Member의 `name`·`nickname`을 변경하지 않는다. 다음 프로필 수정 작업에서 댓글, Creator
신청 운영 목록, 당첨자 조회처럼 Member 이름을 응답하던 조회 모델을 `publicDisplayName()`으로 전환한다.

Creator 활동명은 Member 닉네임과 다른 네임스페이스이며 별도 Creator 작업에서 관리한다.
