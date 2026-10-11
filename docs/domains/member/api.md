# Member API

## GET /api/me

- 권한: Access JWT 필요
- 현재 Member의 기본 정보와 Creator 여부를 반환한다.
- `name`은 OAuth 제공자 원본 이름으로, 공개 표시에는 사용하지 않는다.
- `nickname`이 `null`이면 아직 공개 프로필을 설정하지 않은 회원이다. `profileSetupRequired`는 그 상태를
  화면에서 바로 판단하기 위한 값이다.
- `nicknameChangeableAt`은 마지막 실제 닉네임 변경 후 다시 변경할 수 있는 UTC 오프셋 시각이며, 최초 설정만 했거나
  한 번도 변경하지 않았으면 `null`이다.
- `restorableNickname`은 14일 예약이 유효한 바로 이전 닉네임이다. 없거나 만료됐으면 `null`이다.

```json
{
  "code": "SUCCESS",
  "data": {
    "memberId": 1,
    "name": "OAuth 원본 이름",
    "nickname": "cking_fan",
    "email": "fan@example.com",
    "role": "USER",
    "creator": false,
    "onboardingCompleted": true,
    "profileSetupRequired": false,
    "nicknameChangeableAt": "2026-10-26T12:00:00Z",
    "restorableNickname": "old_fan"
  },
  "message": null
}
```

닉네임을 변경하는 외부 API는 프로필 사진과 함께 다음 프로필 수정 작업에서 추가한다. 그 작업에서 댓글·당첨자 등
공개 이름 응답을 닉네임으로 전환하고, `PROFILE_SETUP_REQUIRED`로 댓글 작성·Creator 신청·이벤트 응모를 제한한다.
따라서 기존 회원의 OAuth 원본 이름을 닉네임으로 자동 이관하거나, 이 PR만으로 기존 공개 이름을 `닉네임 미설정`으로
바꾸지 않는다.
