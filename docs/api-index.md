# 전체 API 인덱스

외부 API와 내부 Service 호출의 탐색용 목록이다. 요청·응답·오류 상세는 공통 API 또는 해당 도메인 문서에서 관리한다. 도메인 문서가 아직 없으면 그 도메인 작업자가 `docs/domains/<도메인>/` 아래에 추가한다.

| No. | 구분 | 도메인 | 역할 | Method | 경로·호출 | 기능 | 멱등성 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 외부 | 공통 | PUBLIC | GET | `/api/users` | 가상 사용자 목록 | - |
| 2 | 외부 | 공통 | USER | POST | `/api/demo/users/select` | 가상 사용자 선택 | - |
| 3 | 외부 | 공통 | PUBLIC | GET | `/api/creators` | Creator 목록 | - |
| 4 | 외부 | 공통 | PUBLIC | GET | `/api/creators/{creatorId}` | Creator 상세 | - |
| 5 | 외부 | Mission | USER | GET | `/api/creators/{creatorId}/missions` | 미션 조회 | - |
| 6 | 외부 | Mission | USER | POST | `/api/creators/{creatorId}/missions/{missionId}/complete` | 미션 완료 | `requestId` |
| 7 | 외부 | Ticket | USER | GET | `/api/creators/{creatorId}/tickets` | Balance 조회 | - |
| 8 | 외부 | Ticket | USER | GET | `/api/creators/{creatorId}/tickets/history` | Ledger 조회 | - |
| 9 | 내부 | EARN | INTERNAL | CALL | `TicketEarnService.earn()` | EARN | `requestId` |
| 10 | 외부 | Event | USER | GET | `/api/events` | Event 목록 | - |
| 11 | 외부 | Event | USER | GET | `/api/events/{eventId}` | Event 상세 | - |
| 12 | 외부 | Entry | USER | POST | `/api/events/{eventId}/entries` | 응모 | `requestId` |
| 13 | 외부 | Entry | USER | GET | `/api/events/{eventId}/entries/me` | 내 응모 조회 | - |
| 14 | 외부 | Creator 운영 | CREATOR | GET | `/api/creator/events` | 내 이벤트 목록 | - |
| 15 | 외부 | Creator 운영 | CREATOR | POST | `/api/creator/events` | Event 생성 | `requestId` |
| 16 | 외부 | Creator 운영 | CREATOR | PATCH | `/api/creator/events/{eventId}` | Event 수정 | 상태 기반 |
| 17 | 외부 | Creator 운영 | CREATOR | DELETE | `/api/creator/events/{eventId}` | Event 삭제 | 상태 기반 |
| 18 | 외부 | Creator 운영 | CREATOR | POST | `/api/creator/events/{eventId}/approval-request` | 승인 요청 | 상태 기반 |
| 19 | 외부 | Admin | ADMIN | GET | `/api/admin/events/pending` | 승인 대기 | - |
| 20 | 외부 | Admin | ADMIN | POST | `/api/admin/events/{eventId}/approve` | Event 승인 | 상태 기반 |
| 21 | 외부 | Admin | ADMIN | POST | `/api/admin/events/{eventId}/reject` | Event 거절 | 상태 기반 |
| 22 | 외부 | Close | CREATOR/ADMIN | POST | `/api/events/{eventId}/close` | 수동 마감 요청 | 상태 기반 |
| 23 | 외부 | Close | ADMIN | GET | `/api/admin/events/{eventId}/closing-status` | 마감 상태만 조회(진행률·Pending·Stream 정보 제외) | - |
| 24 | 외부 | Snapshot | ADMIN | GET | `/api/admin/events/{eventId}/snapshot` | Snapshot 조회 | - |
| 25 | 외부 | Drawing | ADMIN | POST | `/api/admin/events/{eventId}/drawings` | INITIAL Drawing | 상태 기반 |
| 26 | 외부 | Drawing | ADMIN | GET | `/api/admin/drawings/{drawingId}` | Drawing 조회 | - |
| 27 | 외부 | Drawing | ADMIN | POST | `/api/admin/drawings/{drawingId}/retry` | Retry | 상태 기반 |
| 28 | 외부 | Drawing | ADMIN | GET | `/api/admin/drawings/{drawingId}/result` | 결과 조회 | - |
| 29 | 외부 | Winner | PUBLIC | GET | `/api/events/{eventId}/winners` | PUBLIC Drawing의 Winner와 배정 상품 조회 | - |
| 30 | 외부 | Creator 승인 | USER | POST | `/api/creator/applications` | Creator 신청 | 기존 PENDING 재사용 |
| 31 | 외부 | Creator 승인 | USER | GET | `/api/creator/applications/me` | 내 신청 조회 | - |
| 32 | 외부 | Creator 승인 | ADMIN | GET | `/api/admin/creator-applications` | 신청 목록 | - |
| 33 | 외부 | Creator 승인 | ADMIN | POST | `/api/admin/creator-applications/{id}/approve` | 승인 | 상태 기반 |
| 34 | 외부 | Creator 승인 | ADMIN | POST | `/api/admin/creator-applications/{id}/reject` | 거절 | 상태 기반 |
| 35 | 외부 | Drawing | ADMIN | POST | `/api/admin/drawings/{drawingId}/publish` | 완료된 INITIAL·REDRAW Drawing 공개 | 상태 기반 |
| 36 | 외부 | Winner | USER | GET | `/api/me/winners` | 내 당첨 조회 | - |
| 37 | 외부 | Winner | USER | POST | `/api/me/winners/{winnerId}/decline` | 당첨 포기 | 상태 기반 |
| 38 | 외부 | Winner | ADMIN | POST | `/api/admin/winners/{winnerId}/receive` | 수령 완료 | 상태 기반 |
| 39 | 외부 | Winner | ADMIN | POST | `/api/admin/winners/{winnerId}/disqualify` | 자격 박탈 | 상태 기반 |
| 40 | 외부 | Winner | USER/ADMIN | GET | `/api/winners/{winnerId}/history` | 상태 이력 | - |
| 41 | 외부 | Redraw | ADMIN | POST | `/api/admin/events/{eventId}/redraw-requests` | RedrawRequest 생성 | `idempotencyKey` |
| 42 | 외부 | Redraw | ADMIN | GET | `/api/admin/redraw-requests/{redrawRequestId}` | Redraw 상세 | - |
| 43 | 외부 | Redraw | ADMIN | POST | `/api/admin/redraw-requests/{redrawRequestId}/approve` | 승인 | 상태 기반 |
| 44 | 외부 | Redraw | ADMIN | POST | `/api/admin/redraw-requests/{redrawRequestId}/reject` | 거절 | 상태 기반 |
| 45 | 외부 | Redraw | ADMIN | POST | `/api/admin/redraw-requests/{redrawRequestId}/execute` | REDRAW 실행 | 상태 기반 |
| 46 | 외부 | Notification | USER | GET | `/api/me/notifications` | 내 알림 | - |
| 47 | 외부 | Notification | USER | PATCH | `/api/me/notifications/{notificationId}/read` | 알림 읽음 | 상태 기반 |
| 48 | 외부 | Verification | ADMIN | POST | `/api/admin/drawings/{drawingId}/verify` | 추첨 재현 검증 실행 | 상태 기반 |
| 49 | 외부 | Verification | ADMIN | GET | `/api/admin/drawings/{drawingId}/verification-history` | 검증 이력 조회 | - |
| 50 | 내부 | Snapshot | INTERNAL | CALL | `OfficialSnapshotService.createIfAbsent(eventId)` | 공식 Snapshot 생성 | Event 기준 |
| 51 | 내부 | Snapshot | INTERNAL | CALL | `SnapshotIntegrityService.verifyForDrawing(eventId)` | 추첨 전 Snapshot 무결성 검증 | - |
| 52 | 내부 | Event Lifecycle | INTERNAL | CALL | `EventCommandService.open(eventId)` | 예약 Event를 OPEN으로 전이 | Event 행 잠금 |
| 53 | 내부 | Event Lifecycle | INTERNAL | CALL | `EventClosingService.startClosing(eventId)` | Gate 차단·cutoff 확정 후 OPEN Event 마감 시작 | 상태 기반 |
| 54 | 내부 | Event Lifecycle | INTERNAL | CALL | `EventCommandService.completeClosing(eventId)` | Drain 완료 CLOSING Event를 CLOSED로 전이 | Event 행 잠금 |
| 55 | 내부 | Event Lifecycle | INTERNAL | CALL | `EventCommandService.completeDrawing(eventId)` | 초기 추첨 완료 Event를 DRAW_COMPLETED로 전이 | Event 행 잠금 |
| 56 | 내부 | Event Lifecycle | INTERNAL | CALL | `EventCommandService.publish(eventId)` | 추첨 완료 Event를 PUBLISHED로 전이 | Event 행 잠금 |
| 57 | 내부 | 공개 | INTERNAL | CALL | `PublicationService.publish(drawingId, adminId)` | Drawing 공개와 유형별 Winner Notification 생성을 하나의 Transaction으로 처리 | 상태 기반 |
| 58 | 내부 | Drawing | INTERNAL | CALL | `DrawingPublicationService.publish(drawingId, adminId)` | INITIAL은 Event `DRAW_COMPLETED→PUBLISHED`, REDRAW는 Event `PUBLISHED` 유지하며 Drawing 공개 | 상태 기반 |
| 59 | 내부 | Drawing Seed | INTERNAL | CALL | `DrawingSeedService.createForInitial()` | INITIAL Seed 생성·저장 | Drawing 생성 Transaction |
| 60 | 내부 | Drawing Seed | INTERNAL | CALL | `DrawingSeedService.reuseForRetry(seedId)` | 동일 Drawing Seed 조회·재사용 | Seed 기준 |
| 61 | 내부 | Drawing Seed | INTERNAL | CALL | `DrawingSeedService.createForRedraw(previousSeedId)` | 이전과 다른 REDRAW Seed 생성·저장 | Drawing 생성 Transaction |
| 62 | 외부 | Stream | ADMIN | GET | `/api/admin/dead-streams` | Dead Stream 목록 조회 | - |
| 63 | 외부 | Stream | ADMIN | POST | `/api/admin/dead-streams/{id}/replay` | Dead Stream 수동 replay | 상태 기반 |
| 64 | 외부 | Ticket | ADMIN | POST | `/api/admin/tickets/resync` | 잔액 수동 재동기화(Redis를 DB 기준으로) | 상태 기반 |
| 65 | 외부 | Entry | USER | GET | `/api/events/{eventId}/entry-status` | 실시간 응모 현황(참여자 수·누적 응모권) 조회 | - |
| 66 | 외부 | Mission | USER | GET | `/api/missions` | 공용 미션 조회(크리에이터 무관) | - |
| 67 | 외부 | Mission | USER | POST | `/api/missions/{missionId}/complete` | 공용 미션 완료 | `requestId` |
| 68 | 외부 | Ticket | USER | GET | `/api/tickets/common` | 공용 Balance 조회 | - |
| 69 | 외부 | Ticket | USER | GET | `/api/tickets/common/history` | 공용 Ledger 조회 | - |
| 70 | 외부 | Auth | PUBLIC | POST | `/api/auth/token` | Login Code를 Access JWT로 교환 | Login Code 1회 소비 |
| 71 | 외부 | Creator Space Template | ADMIN | POST | `/api/admin/creator-space-templates` | 기본 템플릿 생성 | - |
| 72 | 외부 | Creator Space Template | ADMIN | GET | `/api/admin/creator-space-templates` | 템플릿 목록 | - |
| 73 | 외부 | Creator Space Template | ADMIN | GET | `/api/admin/creator-space-templates/{templateId}` | 템플릿 상세 | - |
| 74 | 외부 | Creator Space Template | ADMIN | PATCH | `/api/admin/creator-space-templates/{templateId}` | 템플릿 수정 | 상태 기반 |
| 75 | 외부 | Creator Space Template | ADMIN | POST | `/api/admin/creator-space-templates/{templateId}/activate` | 템플릿 활성화 | 상태 기반 |

No. 9, No. 50~61은 외부 API가 아니라 내부 Service Method이므로 외부 API 수에 포함하지 않는다.
