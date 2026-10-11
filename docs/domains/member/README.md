# Member 공개 닉네임 정책

`member.name`은 OAuth Provider가 전달한 원본 이름으로, 계정 연결·관리용 정보다. 서비스에서 다른 사용자에게
보이는 이름은 `member.nickname`만 쓴다. 기존 회원은 OAuth 이름을 자동 이관하지 않으며, 설정 전 공개 표기는
`닉네임 미설정`이다.

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

## OAuth와 공개 응답

OAuth 재로그인은 기존 Member의 `name`·`nickname`을 변경하지 않는다. 댓글, Creator 신청 운영 목록, 당첨자
조회처럼 Member 이름을 응답하던 조회 모델은 `publicDisplayName()`을 통해 닉네임만 읽는다.

Creator 활동명은 Member 닉네임과 다른 네임스페이스이며 별도 Creator 작업에서 관리한다.
