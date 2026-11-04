# Creator 신청서·보완 심사 API (#799)

기본 인증·페이지·오류 봉투는 [공통 API](../../common/api.md), 기존 승인·거절 경로는 [Creator API](api.md)를 따른다.
신청자 식별은 JWT `@CurrentMemberId`이고 관리자 경로는 ADMIN JWT와 Application의 역할 재검증을 거친다.

## 신규 신청서

`POST /api/creator/applications`의 새 요청 본문:

```json
{
  "preferredActivityName": "요리하는팬",
  "categoryCodes": ["FOOD"],
  "introduction": "팬들과 요리 과정을 공유하고 다양한 레시피를 소개하는 채널을 운영합니다.",
  "evidenceUrls": ["https://example.com/creator-profile"],
  "activityPlan": "매주 새 콘텐츠를 게시하고 팬과 함께하는 요리 이벤트를 정기적으로 열겠습니다.",
  "platforms": ["YOUTUBE"],
  "agreeToCreatorPolicy": true
}
```

- 희망 활동명은 기존 공개 활동명 형식과 금칙어 정책을 따른다. 승인 시 이름 할당 잠금 아래에서 중복이면 숫자 접미사를 붙인다.
- `categoryCodes`는 현재 활성 관심 분야 taxonomy의 고유 코드 1~3개다. 제출 시 taxonomy 버전을 함께 보존한다.
- `introduction` 30~500자, `activityPlan` 50~1000자, 증빙 HTTPS URL 1~3개(각 2048자 이하), 플랫폼 0~5개다.
- 플랫폼은 `YOUTUBE`, `INSTAGRAM`, `TIKTOK`, `BLOG`, `OTHER`만 허용한다. 정책 동의는 반드시 true다.
- URL은 서버에서 요청하지 않는다. HTTPS 및 host가 필요하고 사용자 정보가 포함된 URL은 거부한다.
- FE는 URL·자유 입력 텍스트를 HTML로 해석하지 않고 이스케이프해 표시한다. 링크는 허용된 HTTPS URL만 이동 대상으로 사용한다.
- 증빙 URL은 참고 자료이지 채널 소유권 증명이 아니다. 주민등록번호·신분증·계좌번호를 제출받지 않는다.
- 성공은 신규 201, 같은 내용의 기존 PENDING 재요청 200이다. 본문이 다른 중복 신청이나 NEEDS_REVISION 중 새 신청은 `APPLICATION_CONTENT_CONFLICT`(409)다. 동일 요청 비교는 저장된 활동명·소개·활동 계획의 앞뒤 공백을 정규화한다.
- 이행 기간 동안 본문 없는 구 FE 요청은 `submissionVersion=0`인 레거시 PENDING을 만든다. 신청서 없이 승인하면 `APPLICATION_FORM_REQUIRED`(409)다. 새 FE가 같은 회원의 신청서를 제출하면 기존 PENDING에 첫 제출 버전을 연결하고 200을 반환한다. 별도 관리자 보완 요청은 필요하지 않다.

## 상태 전이와 보완

`PENDING → NEEDS_REVISION → PENDING → APPROVED/REJECTED` 흐름이며, 관리자는 NEEDS_REVISION에서도 거절할 수 있다. 최종 처리된 신청은 수정·재심사할 수 없다.
동일 회원의 PENDING·NEEDS_REVISION 중복 생성은 막고 REJECTED 이후 새 신청은 허용한다. 기존 Creator가 있는 회원은 새 신청이 불가하다.

### POST /api/admin/creator-applications/{id}/request-revision

PENDING만 가능하다. 요청 `{ "revisionReason": "증빙을 보완해 주세요." }`의 사유는 필수, 최대 500자다.
응답은 `{ "applicationId": 1, "status": "NEEDS_REVISION" }`이다. `reviewedAt`은 최종 승인·거절 시각이므로 그대로 null이고 보완 요청은 별도 이력에 남긴다.

### PATCH /api/creator/applications/{id}

본인의 NEEDS_REVISION 신청만 수정한다. 요청은 신규 신청서와 동일한 전체 Form이다. 저장된 초안만 바꾸고 상태와 제출 버전은 바꾸지 않는다.
응답은 `{ "applicationId": 1, "status": "NEEDS_REVISION" }`이다. 다른 회원은 `FORBIDDEN`(403), 다른 상태는 `APPLICATION_NOT_EDITABLE`(409)이다.

### POST /api/creator/applications/{id}/resubmit

본인의 NEEDS_REVISION 신청에 저장된 전체 초안이 필요하다. 현재 활성 taxonomy 및 신청서 정책을 다시 검증하고 제출 스냅샷을 추가한 뒤 PENDING으로 전환한다. 본문은 없다.
응답은 `{ "applicationId": 1, "status": "PENDING" }`이다. 초안 없음은 `APPLICATION_FORM_REQUIRED`(409)이다.

## 상세 조회

- `GET /api/creator/applications/{id}`: 본인만 접근한다. `draftForm`을 포함하고 내부 심사자 ID는 감춘다.
- `GET /api/admin/creator-applications/{id}`: ADMIN만 접근한다. 심사 중인 미제출 초안은 표시하지 않는다.
- 두 경로는 `submittedForm`, `submissionVersion`, `revisionReason`, `rejectReason`, 버전별 `submissions`, `reviews`를 반환한다. 심사 이력의 `reason`은 신청자에게 전달하는 보완·거절 사유다.
- 관리자 신청 목록과 본인 목록은 기존 페이지 계약에 `revisionReason`, `submissionVersion`을 추가한다. 관리자 상태 필터는 NEEDS_REVISION을 포함한다.

```json
{
  "applicationId": 1,
  "applicantUserId": 10,
  "applicantName": "요리하는팬",
  "status": "NEEDS_REVISION",
  "submittedForm": {"preferredActivityName": "요리하는팬", "taxonomyVersion": "v0.2", "categoryCodes": ["FOOD"], "introduction": "...", "evidenceUrls": ["https://example.com/profile"], "activityPlan": "...", "platforms": ["YOUTUBE"], "agreeToCreatorPolicy": true},
  "draftForm": null,
  "submissionVersion": 1,
  "revisionReason": "증빙을 보완해 주세요.",
  "rejectReason": null,
  "submissions": [{"version": 1, "form": {"preferredActivityName": "요리하는팬"}, "submittedAt": "2026-11-04T00:00:00Z"}],
  "reviews": [{"action": "NEEDS_REVISION", "submissionVersion": 1, "actorId": 2, "reason": "증빙을 보완해 주세요.", "createdAt": "2026-11-04T00:00:01Z"}]
}
```

예시의 Form·Submission은 축약 표기다. 실제 응답은 Form 전체 필드를 반환한다. 모든 시각은 UTC RFC 3339다.

## 승인·거절 및 데이터 보존

승인은 제출 Form과 현재 분야 정책을 재검증하고, 희망 활동명을 최종 할당한다. 제출 뒤 분야가 비활성화되거나 정책이 바뀌어 재검증에 실패하면 `APPLICATION_FORM_REQUIRED`(409)로 보완 필요를 알린다. 승인·Creator·기본 Mission·Space·심사 이력 저장은 한 DB 트랜잭션이다.
거절은 PENDING·NEEDS_REVISION에서 기존 API를 유지하며 이력에도 사유를 남긴다. 보완 대기 중 거절하면 미제출 초안·현재 보완 사유는 비우되 이전 제출 Form과 보완·거절 이력은 보존한다.
`creator_application_review_history`에는 보완 요청·재제출·승인·거절의 행위자·시각·당시 제출 버전을 기록한다.

## 배포

V98은 nullable JSON 컬럼과 신규 테이블만 추가한다. 기존 서버의 INSERT는 `submission_version DEFAULT 0`으로 유지된다.
새 코드가 배포되는 동안에는 NEEDS_REVISION을 쓰는 관리자 보완 요청을 시작하지 않는다. 구 서버는 이 enum을 해석하지 못한다.
모든 서버를 새 코드로 바꾸고 FE 신청·심사 UI를 배포한 뒤 보완 요청을 운영에 노출한다.
본문 없는 구 FE 요청의 호환 경로는 이후 별도 릴리스에서 차단한다. 본문 없는 신청은 언제나 승인 불가다.
이미 NEEDS_REVISION이 저장된 뒤에는 구 버전으로 단순 롤백하지 않는다. 먼저 모든 신청 상태를 안전하게 처리하거나 호환 버전으로 롤백한다.
