# 전체 API 인덱스

외부 API와 내부 Service 호출의 탐색용 목록이다. 요청·응답·오류 상세는 공통 API 또는 해당 도메인 문서에서 관리한다. 도메인 문서가 아직 없으면 그 도메인 작업자가 `docs/domains/<도메인>/` 아래에 추가한다.

| No. | 구분 | 도메인 | 역할 | Method | 경로·호출 | 기능 | 멱등성 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 3 | 외부 | Creator Space | PUBLIC | GET | `/api/creators` | 공개 Creator 목록 조회(이름 검색·페이지) | - |
| 5 | 외부 | Mission | USER | GET | `/api/creators/{creatorId}/missions` | 미션 조회 | - |
| 6 | 외부 | Mission | USER | POST | `/api/creators/{creatorId}/missions/{missionId}/complete` | 미션 완료 | `requestId` |
| 7 | 외부 | Ticket | USER | GET | `/api/creators/{creatorId}/tickets` | Balance 조회 | - |
| 8 | 외부 | Ticket | USER | GET | `/api/creators/{creatorId}/tickets/history` | Ledger 조회 | - |
| 9 | 내부 | EARN | INTERNAL | CALL | `TicketEarnService.earn()` | EARN | `requestId` |
| 10 | 외부 | Event | USER | GET | `/api/events` | Event 목록 | - |
| 11 | 외부 | Event | USER | GET | `/api/events/{eventId}` | Event 상세 | - |
| 12 | 외부 | Entry | USER | POST | `/api/events/{eventId}/entries` | 응모 | `requestId` |
| 13 | 외부 | Entry | USER | GET | `/api/events/{eventId}/entries/me` | 내 응모 조회 | - |
| 14 | 외부 | Creator 운영 | CREATOR | GET | `/api/creator/events` | 내 이벤트 목록(`description`, REJECTED면 최근 반려 사유 `rejectReason` 포함) | - |
| 15 | 외부 | Creator 운영 | CREATOR | POST | `/api/creator/events` | Event 생성 | `requestId` |
| 16 | 외부 | Creator 운영 | CREATOR | PATCH | `/api/creator/events/{eventId}` | Event 수정 | 상태 기반 |
| 17 | 외부 | Creator 운영 | CREATOR | DELETE | `/api/creator/events/{eventId}` | Event 삭제 | 상태 기반 |
| 18 | 외부 | Creator 운영 | CREATOR | POST | `/api/creator/events/{eventId}/approval-request` | 승인 요청 | 상태 기반 |
| 19 | 외부 | Admin | ADMIN | GET | `/api/admin/events/pending` | 승인 대기 | - |
| 134 | 외부 | Admin | ADMIN | GET | `/api/admin/events` | Event 운영 목록 | - |
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
| 165 | 외부 | Ticket | ADMIN | GET | `/api/admin/tickets/history` | 회원별 Creator 전용·공용 응모권 Ledger 이력 조회(cursor) | - |
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
| 76 | 외부 | Auth | PUBLIC | POST | `/api/auth/refresh` | Refresh Cookie를 회전해 Access JWT 갱신 | Refresh Token 1회 소비 |
| 77 | 외부 | Auth | PUBLIC | POST | `/api/auth/logout` | Refresh Token 폐기 및 Cookie 만료 | - |
| 78 | 외부 | Member | USER | GET | `/api/me` | 인증된 현재 사용자 기본 정보·닉네임 설정 상태 조회 | - |
| 79 | 외부 | Ticket | ADMIN | POST | `/api/admin/tickets/common/resync` | 공용 응모권 잔액 수동 재동기화(Redis를 DB 기준으로) | 상태 기반 |
| 80 | 외부 | Creator Space | PUBLIC | GET | `/api/creators/{creatorId}/space` | Creator Space 홈·프로필 조회(팔로워 수 포함) | - |
| 81 | 외부 | Creator Space | CREATOR | GET | `/api/creator/space` | 내 Creator Space 조회 | - |
| 82 | 외부 | Creator Space | CREATOR | PATCH | `/api/creator/space` | 내 Creator Space 홈·프로필 수정 | 상태 기반 |
| 83 | 외부 | 구독 인증 | PUBLIC | GET | `/api/creators/{creatorId}/youtube-channel` | Creator YouTube 채널 조회 | - |
| 84 | 외부 | 구독 인증 | CREATOR | GET | `/api/creator/youtube-channel` | 내 YouTube 채널 설정 조회 | - |
| 85 | 외부 | 구독 인증 | CREATOR | PUT | `/api/creator/youtube-channel` | YouTube 채널 최초 설정·전체 교체 | 상태 기반 |
| 86 | 외부 | 구독 인증 | USER | POST | `/api/creators/{creatorId}/missions/{missionId}/subscription-verifications` | 구독 인증 이미지 제출 | `requestId` |
| 87 | 외부 | 구독 인증 | USER | GET | `/api/subscription-verifications/{verificationId}` | 내 구독 인증 상태 조회 | - |
| 88 | 외부 | 구독 인증 | USER | GET | `/api/creators/{creatorId}/missions/{missionId}/subscription-verifications/me/latest` | 미션의 내 최신 구독 인증 조회 | - |
| 89 | 내부 | Mission | INTERNAL | CALL | `YoutubeSubscriptionMissionProvisioningService.ensureForCreator(creatorId)` | 채널 최초 설정 시 구독 미션 멱등 생성 | Creator·Mission 유형 기준 |
| 90 | 내부 | 구독 인증 | INTERNAL | CALL | `SubscriptionVerificationProcessor.process(verificationId)` | 구독 인증 비동기 판정·보상 시작 | Processing Claim |
| 91 | 내부 | Ticket | INTERNAL | CALL | `TicketOnceEarnService.earn(command)` | 구독 인증 등 평생 1회 보상의 영구 멱등 적립 | requestId·ONCE Business Key |
| 92 | 외부 | Creator Space | PUBLIC | GET | `/api/creator-spaces/{slug}` | 공유 URL slug로 Creator Space 홈·프로필 조회(팔로워 수 포함) | - |
| 93 | 외부 | Creator Space | CREATOR | PATCH | `/api/creator/space/slug` | 내 Creator Space 커스텀 slug 변경 | 상태 기반 |
| 94 | 외부 | Calendar | CREATOR | POST | `/api/creator/calendar/schedules` | 크리에이터 일정 생성 | - |
| 95 | 외부 | Calendar | CREATOR | PATCH | `/api/creator/calendar/schedules/{scheduleId}` | 크리에이터 일정 수정(전체 필드 교체) | 상태 기반 |
| 96 | 외부 | Calendar | CREATOR | DELETE | `/api/creator/calendar/schedules/{scheduleId}` | 크리에이터 일정 삭제(하드 삭제) | 상태 기반 |
| 97 | 외부 | Calendar | CREATOR | GET | `/api/creator/calendar/schedules` | 내 일정 기간 조회 | - |
| 98 | 외부 | Calendar | PUBLIC | GET | `/api/creators/{creatorId}/calendar/schedules` | 크리에이터 캘린더 기간 조회 | - |
| 99 | 외부 | Calendar | PUBLIC | GET | `/api/creators/{creatorId}/calendar/schedules/{scheduleId}` | 크리에이터 일정 상세 조회 | - |
| 100 | 외부 | Mission | USER | POST | `/api/creators/{creatorId}/missions/share/complete` | Creator Space 공유 SHARE 미션 완료 | `requestId` |
| 101 | 외부 | Follow | USER | PUT | `/api/creators/{creatorId}/follow` | 크리에이터 팔로우 | 상태 기반 |
| 102 | 외부 | Follow | USER | DELETE | `/api/creators/{creatorId}/follow` | 크리에이터 언팔로우 | 상태 기반 |
| 103 | 외부 | Follow | USER | GET | `/api/creators/{creatorId}/follow` | 크리에이터 팔로우 여부 조회 | - |
| 104 | 외부 | Follow | USER | GET | `/api/me/follows` | 내 팔로우 목록 | - |
| 105 | 내부 | Follow | INTERNAL | CALL | `CreatorFollowQueryService.isFollowing(memberId, creatorId)` | 다른 도메인의 팔로우 여부 확인 | - |
| 106 | 외부 | Calendar | USER | PUT | `/api/me/calendar/schedules/{scheduleId}` | 개인 캘린더에 일정 담기 | 상태 기반 |
| 107 | 외부 | Calendar | USER | DELETE | `/api/me/calendar/schedules/{scheduleId}` | 개인 캘린더에서 일정 제거 | 상태 기반 |
| 108 | 외부 | Calendar | USER | GET | `/api/me/calendar/schedules` | 개인 캘린더 기간 조회 | - |
| 109 | 외부 | Post | CREATOR | POST | `/api/creator/posts/images` | 게시글 이미지 1장 업로드 | - |
| 110 | 외부 | Post | CREATOR | POST | `/api/creator/posts` | 게시글 작성 | - |
| 111 | 외부 | Post | CREATOR | PATCH | `/api/creator/posts/{postId}` | 게시글 수정(전체 필드 교체) | 상태 기반 |
| 112 | 외부 | Post | CREATOR | DELETE | `/api/creator/posts/{postId}` | 게시글 삭제(하드 삭제) | 상태 기반 |
| 113 | 외부 | Post | PUBLIC | GET | `/api/creators/{creatorId}/posts` | 크리에이터 게시글 목록(팔로워 공개는 잠금 표시) | - |
| 114 | 외부 | Post | PUBLIC | GET | `/api/creators/{creatorId}/posts/{postId}` | 크리에이터 게시글 상세 | - |
| 115 | 외부 | 구독 인증 | ADMIN | GET | `/api/admin/subscription-verifications/{verificationId}/image-reuse` | 이미지 재사용 탐지 감사 결과 조회 | - |
| 116 | 외부 | Post | PUBLIC | GET | `/api/creators/{creatorId}/posts/{postId}/comments` | 게시글 댓글 목록 | - |
| 117 | 외부 | Post | USER | POST | `/api/creators/{creatorId}/posts/{postId}/comments` | 게시글 댓글 작성(팔로워·작성 Creator) | - |
| 118 | 외부 | Post | USER | PATCH | `/api/creators/{creatorId}/posts/{postId}/comments/{commentId}` | 게시글 댓글 수정(작성자) | 상태 기반 |
| 119 | 외부 | Post | USER | DELETE | `/api/creators/{creatorId}/posts/{postId}/comments/{commentId}` | 게시글 댓글 삭제(작성자·게시글 Creator) | 상태 기반 |
| 120 | 외부 | 비정상 행동 탐지 | ADMIN | GET | `/api/admin/abuse-detections` | Detection 목록 조회 | - |
| 121 | 외부 | 비정상 행동 탐지 | ADMIN | GET | `/api/admin/abuse-detections/{detectionId}` | Detection 상세·Evidence 조회 | - |
| 122 | 외부 | 비정상 행동 탐지 | ADMIN | PATCH | `/api/admin/abuse-detections/{detectionId}/review` | Detection 검토 판정 | 상태 기반 |
| 123 | 외부 | Creator 추천 | ADMIN 또는 추천 적재 API Key | PUT | `/api/admin/creators/{creatorId}/similar` | LLM 유사 추천 후보 묶음 검증·원자 교체 | `creatorId + applicationSequence` |
| 124 | 외부 | Creator 추천 | PUBLIC | GET | `/api/creators/{creatorId}/similar` | 저장된 유사 크리에이터 후보 조회 | - |
| 125 | 외부 | Creator 추천 | USER | GET | `/api/me/creator-recommendations` | 관심 분야·팔로우 기반 개인화 Creator 추천 조회 및 recommendationRequestId 발급(`HYBRID`·`INTEREST`·`FOLLOW_PERSONALIZED_V2`, 결과가 비면 `POPULAR_FALLBACK_V1`) | - |
| 126 | 외부 | 관심 분야 | PUBLIC | GET | `/api/interests` | 선택 가능한 관심 분야 목록 조회(활성 분류체계, 세부 주제 포함) | - |
| 127 | 외부 | 관심 분야 | USER | GET | `/api/me/interests` | 내 관심 분야·세부 주제 조회 | - |
| 128 | 외부 | 관심 분야 | USER | PUT | `/api/me/interests` | 내 관심 분야 전체 교체 저장(0~3개, 세부 주제는 상위당 3개·생략 시 유지) | 전체 교체(멱등) |
| 129 | 외부 | 관심 분야 추천 | ADMIN 또는 추천 적재 API Key | PUT | `/api/admin/interests/{interestCode}/recommendations` | LLM 관심 분야별 추천 후보 묶음 검증·원자 교체 | `taxonomyVersion + interestCode + applicationSequence` |
| 130 | 외부 | Auth | PUBLIC | POST | `/api/auth/admin/login` | 관리자 ID/PW를 기존 Access JWT·Refresh Cookie로 교환 | - |
| 131 | 외부 | Post | PUBLIC | GET | `/api/creators/{creatorId}/posts/{postId}/comments/{commentId}/original` | 필터링된 게시글 댓글 원문 조회(작성자 본인·개인정보 차단 댓글 제외) | - |
| 132 | 외부 | Auth | PUBLIC | POST | `/api/admin/auth/refresh` | 관리자 Refresh Cookie를 회전해 ADMIN Access JWT 갱신 | Refresh Token 1회 소비 |
| 133 | 외부 | Auth | PUBLIC | POST | `/api/admin/auth/logout` | 관리자 Refresh Token 폐기 및 관리자 Cookie 만료 | - |
| 135 | 외부 | Redraw | ADMIN | GET | `/api/admin/redraw-requests` | RedrawRequest 운영 목록 조회(요청·실행 상태 선택 필터) | - |
| 136 | 외부 | Post | USER | POST | `/api/creators/{creatorId}/posts/{postId}/comments/{commentId}/reports` | 게시글 댓글 신고(게시글을 볼 수 있는 사용자, 본인 댓글 제외) | 신고자+댓글당 1건(재신고는 기존 신고 반환) |
| 137 | 외부 | Post | ADMIN | GET | `/api/admin/comment-reports` | 신고된 게시글 댓글 목록(댓글별 신고 수·접수 수·사유별 수·처리 상태, 최근 신고 순, `status` 필터) | - |
| 138 | 외부 | Member | USER | PUT | `/api/me/onboarding/complete` | 온보딩 완료(또는 건너뛰기) 기록. 본문 없음, 이미 완료여도 성공(멱등). `GET /api/me`의 `onboardingCompleted`로 신규 가입자 판별(V50, 기존 회원은 전원 true) | 상태 기반 |
| 139 | 외부 | Drawing | ADMIN | GET | `/api/admin/events/{eventId}/drawings/initial` | Event의 INITIAL Drawing 조회 | - |
| 140 | 외부 | Creator 추천 | USER | POST | `/api/me/creator-recommendation-events` | 실제 노출·클릭 최대 50건 원자 수집, JWT 소유권/후보/신규 이벤트 24시간 검증, 보존 중 승인 재전송은 만료 후 멱등 성공 | eventId 및 요청+Creator+타입 |
| 141 | 외부 | AI Quiz | CREATOR | GET | `/api/creator/quizzes` | 내 Quiz 목록(최신 revision 제목·검토 상태, 최근 생성 순) | - |
| 142 | 외부 | AI Quiz | CREATOR | GET | `/api/creator/quizzes/{quizId}` | 내 Quiz 상세(공개 상태, 게시 revision, revision 목록) | - |
| 143 | 외부 | AI Quiz | CREATOR | GET | `/api/creator/quizzes/{quizId}/revisions/{revisionId}` | revision 상세(정답·해설·근거·원본 텍스트 포함) | - |
| 144 | 외부 | AI Quiz | CREATOR | POST | `/api/creator/quizzes/{quizId}/revisions/{revisionId}/approve` | 저장된 ALL_PASS 평가를 근거로 Creator 명시적 승인 | 상태 기반 |
| 145 | 외부 | AI Quiz | CREATOR | POST | `/api/creator/quizzes/{quizId}/revisions/{revisionId}/reject` | revision 거절(선택 사유) | 상태 기반 |
| 146 | 외부 | AI Quiz | CREATOR | POST | `/api/creator/quizzes/{quizId}/publish` | 승인된 최신 revision 게시(고정한 Judge result ID/hash 검증) | 상태 기반 |
| 147 | 외부 | AI Quiz | CREATOR | POST | `/api/creator/quizzes/{quizId}/unpublish` | 게시 취소(Judge 상태와 무관) | 상태 기반 |
| 148 | 외부 | AI Quiz | PUBLIC | GET | `/api/creators/{creatorId}/quizzes` | 게시 Quiz 목록(최근 게시 순) | - |
| 149 | 외부 | AI Quiz | PUBLIC | GET | `/api/creators/{creatorId}/quizzes/{quizId}` | 게시 Quiz 상세(문항·보기·videoId, 정답·해설 제외) | - |
| 150 | 외부 | AI Quiz | CREATOR | POST | `/api/creator/quizzes/generations` | YouTube URL 기반 Quiz 비동기 생성 요청(202, 문항 3·5개·보기 4개, Creator당 진행 중 1개, 기능 꺼짐 시 503) | `requestId` |
| 151 | 외부 | AI Quiz | CREATOR | GET | `/api/creator/quizzes/generations/{generationId}` | 내 Quiz 생성 작업 상태(성공 시 quizId·revisionId, 실패 코드) | - |
| 152 | 외부 | Member | USER | POST | `/api/me/profile-images` | 내 프로필 사진 업로드 후 연결용 imageKey 반환 | - |
| 153 | 외부 | Member | USER | PUT | `/api/me/profile` | 닉네임 수정·프로필 사진 교체/삭제(키 생략 시 유지) | 상태 기반 |
| 154 | 외부 | AI Quiz | CREATOR | POST | `/api/creator/quizzes/{quizId}/revisions/{revisionId}/judge-evaluations` | GPT Judge 비동기 평가 접수(202, 최대 2 attempts, Creator당 활성 Job 1개) | `requestId` |
| 155 | 외부 | AI Quiz | CREATOR | GET | `/api/creator/quizzes/{quizId}/revisions/{revisionId}/judge-evaluations/{evaluationId}` | 본인 평가 Job 상태와 문항별 판정 조회 | - |
| 156 | 외부 | Creator | CREATOR | GET | `/api/creator/profile` | 내 Creator 활동명·설정 상태 조회 | - |
| 157 | 외부 | Creator | CREATOR | PUT | `/api/creator/profile` | 내 Creator 활동명 전체 교체·14일 변경/복귀 정책 적용 | 상태 기반 |
| 158 | 외부 | Post | ADMIN | POST | `/api/admin/comment-reports/{commentId}/hide` | 신고된 댓글 숨김(접수 신고 일괄 처리 완료, 되돌릴 수 없음) | 상태 기반 |
| 159 | 외부 | Post | ADMIN | POST | `/api/admin/comment-reports/{commentId}/reject` | 신고된 댓글의 접수 신고 일괄 반려 | 상태 기반 |
| 160 | 외부 | AI Quiz | USER | POST | `/api/creators/{creatorId}/quizzes/{quizId}/attempts` | Fan 답안 제출·서버 채점·동일 요청 replay(200) | `requestId` |
| 161 | 외부 | AI Quiz | USER | GET | `/api/creators/{creatorId}/quizzes/{quizId}/my-attempt` | 본인 응시 시점 결과 조회(게시 취소 후에도 유지) | - |
| 162 | 외부 | Creator | CREATOR | POST | `/api/creator/space/profile-images` | Creator Space 프로필 사진 업로드 | - |
| 163 | 외부 | Creator | CREATOR | PUT | `/api/creator/space/profile-image` | 업로드한 Space 프로필 사진 연결 | - |
| 164 | 외부 | Ticket | ADMIN | GET | `/api/admin/tickets/balance` | 지정 회원의 Redis·MySQL 응모권 잔액 관측·차이 조회 | - |
| 165 | 외부 | Post | USER | POST | `/api/creators/{creatorId}/posts/{postId}/likes` | 게시글 좋아요(팔로워 공개는 팔로워·작성 Creator 본인). 성공 시 해당 Creator의 LIKE 미션 보상 요청 | 회원+게시글당 1건(재요청은 현재 상태 반환) |
| 166 | 외부 | Post | USER | DELETE | `/api/creators/{creatorId}/posts/{postId}/likes` | 게시글 좋아요 취소(응모권 회수 없음) | 상태 기반 |
| 167 | 외부 | Sanction | ADMIN | GET | `/api/admin/sanctions` | 회원 제재 목록(자동 경고·수동 제한, 시작 최신순, `memberId`·`active` 필터) | - |
| 168 | 외부 | Sanction | ADMIN | POST | `/api/admin/sanctions` | 회원에게 댓글 작성 제한을 수동으로 설정(경고 횟수에 세지 않음) | 상태 기반 |
| 169 | 외부 | Sanction | ADMIN | POST | `/api/admin/sanctions/{sanctionId}/release` | 유효한 제한 해제(이미 끝났거나 해제된 제한은 그대로 성공) | 상태 기반 |
| 170 | 외부 | Sanction | CREATOR | POST | `/api/creator/comment-blocks` | 자기 스페이스에서 회원의 댓글 작성·수정을 1~365일 차단(경고 횟수에 세지 않음). 이미 유효한 차단이 있으면 기존 차단 반환 | 회원+크리에이터당 유효한 차단 1건 |
| 171 | 외부 | Sanction | CREATOR | GET | `/api/creator/comment-blocks` | 자기 스페이스 차단 목록(시작 최신순, `active` 필터) | - |
| 172 | 외부 | Sanction | CREATOR | POST | `/api/creator/comment-blocks/{sanctionId}/release` | 자기가 건 차단 해제(이미 끝났거나 해제된 차단은 그대로 성공) | 상태 기반 |
| 173 | 외부 | Sanction | ADMIN | POST | `/api/admin/comment-blocks` | 크리에이터를 지정해 그 스페이스에서 회원의 댓글 작성·수정을 1~365일 차단. 이미 유효한 차단이 있으면 기존 차단 반환 | 회원+크리에이터당 유효한 차단 1건 |
| 174 | 외부 | Sanction | ADMIN | GET | `/api/admin/comment-blocks` | 모든 스페이스의 차단 목록(시작 최신순, `creatorId`·`memberId`·`active` 필터) | - |
| 175 | 외부 | Sanction | ADMIN | POST | `/api/admin/comment-blocks/{sanctionId}/release` | 크리에이터나 관리자가 건 스페이스 차단 해제(이미 끝났거나 해제된 차단은 그대로 성공) | 상태 기반 |
| 176 | 외부 | Post | ADMIN | DELETE | `/api/admin/comments/{commentId}` | 신고 여부와 상관없이 댓글 물리 삭제(204, 신고도 함께 삭제, 경고·알림 없음) | 상태 기반 |
| 177 | 외부 | AI Quiz | CREATOR | POST | `/api/creator/quizzes/{quizId}/revisions` | 최종 문항 선택·수정·추가 저장(신규201/replay200) | Quiz/requestId |
| 178 | 외부 | AI Quiz | CREATOR | POST | `/api/creator/quizzes/{quizId}/revisions/{revisionId}/creator-approval` | 최종 Revision hash와 명시적 책임 승인 | 상태 기반 |
| 179 | 외부 | Creator | CREATOR | POST | `/api/creator/space/banner-images` | Creator Space 배너 이미지 업로드(JPEG/PNG 5MB 이하, 최소 600×200px, 프로필 사진과 같은 저장 경로) | - |
| 180 | 외부 | Creator | CREATOR | PUT | `/api/creator/space/banner-image` | 업로드한 Space 배너 이미지 연결(같은 key 재요청은 멱등) | - |
| 181 | 외부 | Ticket | USER | GET | `/api/me/tickets` | 내 응모권 요약(공용 잔액·팔로우한 Creator별 잔액·서버 계산 합계, DB 기준 표시용) | - |
| 182 | 외부 | Creator Space Template | ADMIN | POST | `/api/admin/creator-space-templates/profile-images` | 템플릿 기본 프로필 이미지 업로드(JPEG/PNG 5MB 이하, 최소 200×200px, CDN `imageUrl` 반환) | - |
| 183 | 외부 | Creator Space Template | ADMIN | POST | `/api/admin/creator-space-templates/banner-images` | 템플릿 기본 배너 이미지 업로드(JPEG/PNG 5MB 이하, 최소 600×200px, CDN `imageUrl` 반환) | - |
No. 9, No. 50~61, No. 89~91, No. 105는 외부 API가 아니라 내부 Service Method이므로 외부 API 수에 포함하지 않는다.
