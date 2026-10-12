# Member API

## GET /api/me

- 권한: Access JWT 필요
- 현재 Member의 기본 정보와 Creator 여부를 반환한다.
- `name`은 OAuth 제공자 원본 이름으로, 공개 표시에는 사용하지 않는다.
- `nickname`이 `null`이면 아직 공개 프로필을 설정하지 않은 회원이다. `profileConfigured`는 그 상태를
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
    "profileImageUrl": "https://cdn.example.com/member-profile-images/1/uuid.jpg",
    "email": "fan@example.com",
    "role": "USER",
    "creator": false,
    "onboardingCompleted": true,
    "profileConfigured": true,
    "nicknameChangeableAt": "2026-10-26T12:00:00Z",
    "restorableNickname": "old_fan"
  },
  "message": null
}
```

`name`은 본인만 보는 OAuth 원본 이름이다. 댓글·당첨자 등 공개 표시는 닉네임을 사용하며, 기존 회원을 자동 이관하지
않으므로 닉네임 전에는 `닉네임 미설정`으로 표시한다. 닉네임 미설정 회원은 댓글 작성·수정, Creator 신청, 이벤트
응모에서 `PROFILE_SETUP_REQUIRED`(409)를 받는다.

## POST /api/me/profile-images

- 권한: Access JWT 필요
- Content-Type: `multipart/form-data`, part 이름 `image`
- 실제 JPEG/PNG 5MB 이하, 최소 200×200px 이미지만 허용한다. 공통 정규화 뒤 JPEG로 저장한다.
- 업로드 결과 키는 현재 Member 전용이며, 24시간 안에 `PUT /api/me/profile`로 연결하지 않으면 삭제된다.

```json
{
  "code": "SUCCESS",
  "data": { "imageKey": "member-profile-images/1/uuid.jpg" },
  "message": null
}
```

## PUT /api/me/profile

- 권한: Access JWT 필요
- `nickname`과 `profileImageKey`를 모두 보내는 전체 교체다.
- `profileImageKey`에 현재 키를 보내면 사진을 유지하고, `null`이면 기본 이미지로 바꾼다.
- 다른 회원의 키, 이미 연결·삭제 대기·존재하지 않는 키는 `PROFILE_IMAGE_UNAVAILABLE`(400)이다.

```json
{
  "nickname": "cking_fan",
  "profileImageKey": "member-profile-images/1/uuid.jpg"
}
```

성공 응답은 `GET /api/me`와 같다. `profileImageUrl`은 만료되지 않는 CDN URL이며 기본 이미지를 쓸 때는 `null`이다.
