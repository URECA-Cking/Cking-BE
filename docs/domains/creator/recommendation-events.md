# 추천 행동 수집 계약 (이슈 #500)

## 추천 응답과 스냅샷

`GET /api/me/creator-recommendations`는 `recommendationRequestId`(UUID)를 추가한다.
응답마다 새 ID를 발급하며 빈 결과도 스냅샷을 남긴다. 발급은 노출이 아니다.
서버는 JWT 회원, policyVersion, UTC 생성/만료 시각, 실제 반환 카드의 1부터 시작하는 응답 순위를 저장한다.
기여한 source 각각의 종류, seed/관심 코드, taxonomyVersion, generationId, method, modelVersion,
source 원래 순위도 같은 조회에서 복사한다. 혼합 후보를 단일 모델로 표시하지 않는다. 인기 fallback은 source가 없다.
소개 원문, 이름, 이미지, JWT, API Key는 분석 저장 대상이 아니다.

스냅샷 저장은 별도 트랜잭션이다. 저장 실패 시 추천은 정상 반환하고 ID는 `null`이다.
FE는 null ID에 대해 수집 요청을 보내지 않는다. 실패한 스냅샷을 나중에 복원하지 않는다.

## POST /api/me/creator-recommendation-events

Bearer Access JWT 필수. 호출자는 `@CurrentMemberId`이며 memberId, 순위, 정책, 클라이언트 시각은 받지 않는다.

```json
{"events":[{
  "eventId":"5fe5b350-231a-44b3-8b1c-fd28bf9c51b9",
  "recommendationRequestId":"3be47637-f3b7-4456-a587-7ad2fe85b818",
  "creatorId":20,
  "eventType":"IMPRESSION"
}]}
```

- events는 1~50건, UUID 필수, creatorId는 양수, eventType은 `IMPRESSION` 또는 `CLICK`이다.
- 성공: HTTP 200, `data: {"acceptedCount": 1}`. 중복을 포함해 승인된 입력 수이며 새 지표 수가 아니다.
- 배치는 원자적이다. 한 항목이라도 실패하면 전체를 롤백한다. 입력을 수정한 뒤 배치 전체를 재전송한다.
- 최초 승인 서버 수신 시각(UTC, 마이크로초)이 집계 시각이다. 클라이언트 발생 시각으로 소급하지 않는다.
- 신규 이벤트의 수신 시각이 요청 만료 시각 이상이면 거부한다. 기본 수명은 24시간이다.
  보존 중인 승인 영수증과 ID/내용이 동일한 재전송은 만료 후에도 성공하며, 최초 수신 시각을 유지한다.
  소유권 확인은 재전송에도 적용한다. 내용 충돌은 만료 여부보다 먼저 409로 거부한다.
  보존 정리로 스냅샷/영수증이 삭제된 뒤에는 404다.
- 요청 소유권과 실제 반환 카드 포함 여부를 서버에서 검증한다. 현재 추천 세대가 바뀌어도 원래 스냅샷을 쓴다.
- eventId는 전역 멱등 키다. 동일 ID/동일 내용은 승인, 동일 ID/다른 내용은 충돌이다.
- 서로 다른 ID라도 `(recommendationRequestId, creatorId, eventType)`은 최초 한 건만 집계한다.
  승인한 ID 별 영수증을 보존하므로 중복 카드에 사용한 ID도 다른 내용으로 재사용할 수 없다.
  새 행동 ID의 재클릭은 고유 카드 클릭 수를 늘리지 않지만 마지막 클릭 귀속 시각에는 반영한다.
  동일 eventId 재전송은 최초 승인 시각을 유지하고 귀속 기간을 연장하지 않는다.
- 클릭 선도착을 허용하고 노출을 자동 생성하지 않는다. 같은 카드의 노출이 나중에 도착하면 연결한다.
- DB 오류는 5xx다. FE는 같은 eventId/내용으로 재시도한다. 응답 유실 후 재전송도 지표가 증가하지 않는다.

| 코드 | HTTP | 상황 |
| --- | --- | --- |
| VALIDATION_FAILED | 400 | 필드, UUID, 타입, 1~50건 제한 오류 |
| UNAUTHORIZED | 401 | JWT 누락/무효 |
| FORBIDDEN | 403 | 다른 회원의 요청 |
| RESOURCE_NOT_FOUND | 404 | JWT에 대응하는 회원이 없음 |
| RECOMMENDATION_REQUEST_NOT_FOUND | 404 | 스냅샷 없음/정리됨 |
| RECOMMENDATION_CANDIDATE_NOT_RETURNED | 400 | 반환하지 않은 카드 |
| RECOMMENDATION_REQUEST_EXPIRED | 410 | 수집 유효 기간 만료 |
| RECOMMENDATION_EVENT_CONFLICT | 409 | eventId 내용 충돌 |
| SYSTEM_ERROR | 500 | 저장 실패, 동일 내용으로 재시도 가능 |

## 팔로우 전환

기존 PUT 팔로우의 실제 미팔로우→팔로우 전환만 도메인 이벤트를 발행한다.
동일 회원의 팔로우/언팔로우/수집은 별도 `member_activity_lock` 행으로 직렬화하며 JDBC update count의 드라이버별 차이에 의존하지 않는다.
신규 관계와 원본 팔로우 이벤트를 같은 트랜잭션에 저장한다. 원본 이벤트 저장 실패 시 팔로우도 롤백한다.
원본 이벤트는 언팔로우 후에도 보존하며, 커밋 알림/큐/전환 저장 실패는 주기적 복구 대상이다.
팔로우 트랜잭션 커밋 이후 별도 트랜잭션에서 동일 회원/Creator의 직전 24시간 내 최종 승인 CLICK에 귀속한다.
팔로우 전환 시각을 상한으로 사용해 뒤늦게 수신한 클릭에는 소급 귀속하지 않는다. 하한은 포함한다.
스냅샷 생성/수집 승인/팔로우 시각은 동일 DB의 `UTC_TIMESTAMP(6)`을 사용한다.
동시각 클릭은 eventId 사전순 내림차순으로 결정한다. 귀속 정책은 `LAST_CLICK_V1`이고 실제 적용 window도 저장한다.
요청/Creator별 최대 1건이다. 반복 PUT, 동시 PUT, 언팔로우 후 같은 추천 클릭으로 재팔로우해도 늘지 않는다.
가장 최근 클릭이 이미 전환됐으면 더 오래된 클릭을 찾아 추가 귀속하지 않는다. 클릭 없음/기간 밖이면 전환 없음이다.
Creator 상세에서 팔로우해도 저장된 클릭으로 연결되며 클라이언트 FOLLOW 이벤트는 허용하지 않는다.
이 값은 관측 지표이며 실험에 의한 인과 효과가 아니다.

## FE 지침

1. 응답 ID와 카드 creatorId를 함께 유지한다. 새 추천 응답에는 새 ID를 사용한다.
2. DOM 생성만으로 노출을 보내지 않는다. 화면에 실제 표시된 카드에 대해 IntersectionObserver 등으로 IMPRESSION을 보낸다.
3. 실제 카드 클릭 시 CLICK을 보내며 탐색 전에 전송을 시도한다. 클릭 때문에 화면 이동을 막지 않는다.
4. 행동마다 `crypto.randomUUID()`를 생성하고, 재시도 큐에는 ID와 내용을 그대로 보존한다.
5. 최대 50건씩 전송한다. 5xx/네트워크 오류는 제한된 backoff로 재시도하고 만료/4xx는 무한 재시도하지 않는다.
6. 서버가 클릭만 받았을 때 노출을 추정하지 않으므로 실제 노출 이벤트도 별도로 보내야 한다.

운영/집계/보존은 [추천 행동 운영](../../operations/creator-recommendation-events.md)을 따른다.
