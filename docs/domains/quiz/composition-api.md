# Creator Quiz 문항 구성·책임 승인

두 API는 Creator JWT가 필요하며 호출자는 `@CurrentMemberId`로 식별한다. 다른 Creator의 Quiz는 403이다.
공통 응답 봉투와 기존 공개·Fan API는 유지한다. 이 기능은 Provider를 호출하지 않는다.

## POST /api/creator/quizzes/{quizId}/revisions

신규 저장은 201, 같은 요청 replay는 200이다.

```json
{
  "requestId": "750e8400-e29b-41d4-a716-446655440000",
  "baseRevisionId": 100,
  "baseRevisionSha256": "부모 revision의 소문자 SHA-256",
  "questions": [
    {"sourceQuestionId": 1001},
    {"sourceQuestionId": 1003, "content": {
      "question": "수정한 질문", "options": ["A", "B", "C", "D"],
      "correctOptionIndex": 1, "explanation": "정답 설명", "sourceEvidence": ""
    }},
    {"content": {
      "question": "직접 추가한 질문", "options": ["A", "B", "C", "D"],
      "correctOptionIndex": 0, "explanation": "정답 설명"
    }}
  ]
}
```

- `sourceQuestionId`만 보내면 부모 문항을 그대로 복사한다. `content`를 함께 보내면 내용을 교체한다.
- ID 없이 `content`만 보내면 추가 문항이다. 포함하지 않은 부모 문항은 제외하며 원본은 삭제하지 않는다.
- 최종 1~5문항, 문항마다 정확히 4개 보기. 질문 500자, 보기 200자, 해설 1000자까지이며 필수다.
- 정답은 0~3. 질문·보기의 NFC 및 연속 공백을 정규화한 중복을 거부한다. 저장 문자열은 앞뒤 공백만 제거한다.
- 수정·추가의 근거는 선택이며 최대 1000자. 누락/null/공백만 입력하면 빈 문자열이다. 부모 근거를 상속하지 않는다.
- 복사한 원본의 근거는 유지한다. 기존 4지선다가 아닌 문항은 4지선다로 수정해야 최종 집합에 포함할 수 있다.
- 번호는 배열 순서대로 `questionNo=0..N-1`, `optionNo=0..3`. FE 표시만 1부터 시작한다.
- 원본의 PASS/FAIL/UNCERTAIN/미평가 여부는 선택·책임 승인을 막지 않는다.
- 신규 요청은 최신 부모 ID/hash와 미게시 이력을 확인한다. 게시 취소 후에도 신규 편집은 불가하다.
- 성공한 UUID replay는 최신 여부·게시 잠금보다 먼저 판정하므로 승인·게시 이후에도 복구할 수 있다.

### 멱등성과 원자성

V74의 `(quiz_id, request_id)` UNIQUE로 성공한 접수를 기록한다. 서버가 정규화 payload의
`quiz-composition-request-v1` SHA-256을 계산한다. UUID는 hash에서 제외한다. 부모 ID/hash, 선택 순서,
source ID, COPY/CONTENT 구분, 질문·보기 순서·정답·해설·근거를 UTF-8 길이 접두어로 포함한다.
동일 의미의 null/공백 근거는 같고, 보기·문항 순서 또는 COPY/CONTENT 요청 형태가 바뀌면 다른 요청이다.

Quiz 행 잠금과 READ_COMMITTED 안에서 Revision·문항·보기·접수 기록을 함께 저장한다.
동일 UUID는 replay, 다른 UUID로 같은 부모를 동시에 편집하면 한 요청은 stale 충돌이다.
UNIQUE/잠금 실패는 트랜잭션 종료 후 별도 조회에서만 replay를 복구한다. 복구 근거가 없으면 실패를 유지한다.

## POST /api/creator/quizzes/{quizId}/revisions/{revisionId}/creator-approval

```json
{"revisionSha256": "확인한 최종 revision SHA-256", "acknowledgeResponsibility": true}
```

200으로 결정을 반환한다. 최신 `CREATOR_COMPOSED`의 정확한 hash와 명시적 책임 확인이 필요하다.
`CREATOR_REVIEW_V1`에 승인자·시각·승인 hash를 고정하며 Judge 근거를 요구하지 않는다.
동일 책임 승인 replay는 기존 결정을 반환한다. 다른 결정 또는 hash는 409다.
처음 게시 전 재편집하면 새 PENDING_REVIEW Revision이며 다시 승인해야 한다.

기존 `/approve`는 AI 원본의 `LEGACY_JUDGE_V1`·ALL_PASS 정책을 유지한다.
Creator 구성본은 새 책임 승인 API를 사용한다. 기존 publish/unpublish의 경로와 요청은 유지한다.
게시 시 승인 정책별로 저장 내용과 근거를 검증한다. 최초 게시한 Revision/hash를 고정하며 취소 후에도
다른 내용으로 변경할 수 없다. 같은 고정 Revision 재게시만 가능하다.

## Creator 응답 확장

저장 응답은 기존 Creator revision 상세 형식이며 아래 필드를 추가한다. 기존 필드를 제거하지 않는다.

| 필드 | 의미 |
| --- | --- |
| origin / parentRevisionId / hashVersion / revisionSha256 | AI 원본과 Creator 구성본·부모·해시 구분 |
| approvalPolicyVersion | 현재 승인 정책. 미승인·반려는 null |
| contentLocked / canEdit | 게시 이력 잠금, 현재 최신 revision의 편집 가능 여부 |
| questionCount / optionCount | 최종 문항 수·4개 보기 |
| questions[].questionId | 다음 편집의 sourceQuestionId |
| originQuestionId / previousQuestionId / changeType | 원본·직전 문항 계보 |
| evaluationStatus | PASS / FAIL / UNCERTAIN / NOT_REVALIDATED / NOT_EVALUATED |
| sourceEvaluation | 원본 result ID/hash·revision ID·원본 questionNo·7개 verdict/reason 참고값 |

수정 이력이 있으면 원본 내용으로 되돌려도 NOT_REVALIDATED다. 원본 PASS는 참고 영역에만 제공한다.
추가 문항은 NOT_EVALUATED다. sourceEvaluation이 없으면 null이다. Creator 상세에도 최신 구성의 origin,
parentRevisionId,hashVersion,approvalPolicyVersion,contentLocked,canEdit를 추가한다.
구성본의 promptVersion은 부모 AI 생성 버전 참고값이며 새 AI 호출을 뜻하지 않는다.
이 정보는 공개 응답에 추가하지 않는다. Fan 제출 후 본인 결과의 풀이 공개와 Ticket 계약은 유지한다.

## 오류·검증 경계

| code | HTTP | 조건 |
| --- | --- | --- |
| VALIDATION_FAILED | 400 | 필수·UUID·문항 구조·중복·정답 범위 오류, 부모 밖 문항 참조 |
| UNAUTHORIZED / FORBIDDEN | 401 / 403 | 인증 없음 / Creator 자격 없음·타인 Quiz |
| QUIZ_NOT_FOUND / QUIZ_REVISION_NOT_FOUND | 404 | 대상 없음·부모 소속 불일치 |
| QUIZ_REVISION_CONFLICT | 409 | stale 부모·틀린 hash·책임 승인 대상 종류 불일치 |
| QUIZ_EDIT_REQUEST_CONFLICT | 409 | 같은 UUID의 다른 payload |
| QUIZ_CONTENT_LOCKED | 409 | 게시 이력 이후 신규 편집·다른 Revision 게시 |
| QUIZ_REVISION_ALREADY_DECIDED | 409 | 다른 결정이 이미 존재 |
| QUIZ_REVISION_INTEGRITY_FAILED | 500 | 저장 내용·계보·승인 hash 변조 |

V73 → V74 적용이 필요하다. migration 번호는 PR 전 최신 develop·열린 PR과 다시 대조한다.
로컬 검증은 컴파일·순수 단위 테스트이며 WebMvc/Spring/MySQL 경쟁·rollback 검증은 CI에서 실행한다.
테스트 skip은 검증 완료가 아니다. 실제 Provider 및 Ticket Worker 활성화는 별도 승인 범위다.
