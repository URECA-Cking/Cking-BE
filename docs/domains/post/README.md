# Post

Creator Space 게시물 탭의 게시글과 댓글을 다룬다. 외부 API 계약은 [Post API](api.md)와 [Post Comment API](comment-api.md)를 따른다. AI 댓글 필터링은 모델 비교 후 별도 이슈에서 다룬다.

## 책임

- Creator 본인의 게시글 작성·수정·삭제와 게시글 이미지 업로드
- 공개 범위(PUBLIC·FOLLOWERS)에 따른 게시글 공개 조회
- 게시글 이미지 업로드 기록으로 소유자·연결 검증과 저장소 정리
- 게시글 댓글 조회·작성·수정·삭제

Member·Creator는 읽기만 한다. 팔로우 여부는 Follow 도메인의 `CreatorFollowQueryService.isFollowing`으로 확인한다. 게시글과 댓글의 조회·참여 권한 판단은 `PostAccessPolicy` 한 곳에 둔다.

## 소유 데이터

V35의 `creator_post`(게시글)와 `creator_post_image`(업로드 기록), V36의 `creator_post_comment`(댓글)를 소유한다. 게시글과 댓글은 하드 삭제하며, 게시글을 삭제하면 같은 Transaction에서 댓글을 먼저 삭제한다.

## 댓글 권한

| 동작 | 허용 |
| --- | --- |
| 조회 | 게시글을 볼 수 있는 사람. PUBLIC은 누구나, FOLLOWERS는 팔로워와 작성 Creator 본인 (그 외 `POST_FOLLOWERS_ONLY`) |
| 작성 | 로그인 + 팔로워 또는 작성 Creator 본인. PUBLIC 게시글도 팔로우해야 한다 (그 외 `COMMENT_FOLLOWERS_ONLY`) |
| 수정 | 댓글 작성자 본인이면서 작성과 같은 조건 |
| 삭제 | 댓글 작성자 본인, 게시글 작성 Creator 본인. 공개 범위·팔로우를 보지 않으므로 팔로우를 끊은 작성자도 자기 댓글을 지울 수 있다 |

AI 필터링이 들어오기 전까지는 게시글 작성 Creator의 댓글 삭제가 댓글 관리 수단이다.

댓글 작성·수정·삭제는 게시글 행을 공유 잠금(`FOR SHARE`)으로 읽는다. 게시글 수정·삭제는 쓰기 잠금(`FOR UPDATE`)을 잡으므로, 동시에 진행 중인 게시글 삭제가 끝날 때까지 기다렸다가 사라진 게시글을 보고 404로 응답한다(잠금이 없으면 댓글 저장이 FK 오류로 500이 된다). 공유 잠금끼리는 충돌하지 않아 같은 게시글의 댓글 쓰기는 서로 기다리지 않는다. 댓글 수정·삭제는 이어서 댓글 행을 쓰기 잠금으로 읽어, 같은 댓글의 동시 수정·삭제도 직렬화한다(먼저 삭제되면 404). 잠금 순서는 항상 게시글 → 댓글이며 게시글 삭제도 같은 순서라 교착이 생기지 않는다. 댓글 목록 조회는 잠그지 않는다.

## 공개 범위

| 조회자 | PUBLIC | FOLLOWERS |
| --- | --- | --- |
| 비로그인·팔로우하지 않은 사용자 | 본문·이미지 | 목록은 잠금(`locked=true`, 본문 null, 이미지 없음, `imageCount`만 제공), 상세는 `POST_FOLLOWERS_ONLY`(403) |
| 팔로워, 작성한 Creator 본인 | 본문·이미지 | 본문·이미지 |

이미지는 권한을 확인한 뒤에만 유효 시간 10분의 조회 주소(`presignedGetUrl`)를 발급한다. 발급은 최선 노력이라 실패한 이미지는 `url`을 null로 두고, 게시글 저장이나 목록 전체 응답을 실패시키지 않는다.

작성·수정 응답은 쓰기 Transaction 안에서 만든다. Commit 뒤에 응답 생성 단계(재조회·주소 발급)가 실패해 "저장은 됐는데 실패 응답"이 되는 경우를 없애기 위함이다. 응답 자체를 받지 못한 네트워크 재시도는 별도 멱등성 키가 없으므로, 이미지 없는 게시글은 중복 생성될 수 있다(이미지가 있으면 두 번째 요청은 `POST_IMAGE_UNAVAILABLE`).

## 이미지

업로드는 요청 1건당 1장이다. 공통 이미지 모듈(`docs/common/image.md`)에 게시글 정책(최소 200×200, 긴 변 2048px)을 적용해 JPEG로 정규화하고 `post-images/{creatorId}/{UUID}.jpg`로 저장한다. 게시글당 최대 5장이며, 작성·수정 요청의 key 순서가 표시 순서다.

### 업로드 기록 상태

| 상태 | 의미 |
| --- | --- |
| `UPLOADING` | 저장소 put 전에 먼저 기록한다. put이 실패해도 기록이 남아 정리할 수 있다. |
| `UPLOADED` | put 완료. `post_id`가 NULL이면 아직 게시글에 연결되지 않았다. |
| `DELETE_PENDING` | 저장소 삭제 대기. 저장소 delete 성공 후 행을 삭제한다. |

### 불변조건

- 게시글 연결은 `object_key IN (…) AND creator_id = 본인 AND status = UPLOADED AND post_id IS NULL` 조건부 UPDATE로만 한다. 변경된 행 수가 요청 key 수와 다르면 `POST_IMAGE_UNAVAILABLE`로 거부하고 Transaction 전체를 Rollback한다. 남의 key, 이미 연결된 key, 동시에 같은 key를 쓰는 요청, 정리 대상이 된 key가 함께 막힌다.
- 같은 게시글의 수정·삭제는 게시글 행 잠금(`PESSIMISTIC_WRITE`)으로 직렬화한다.
- 게시글에서 빠진 이미지와 삭제된 게시글의 이미지는 게시글 변경과 같은 Transaction에서 `DELETE_PENDING`으로 바꾸고 연결을 해제한다. 저장소 삭제는 Commit 후(`AFTER_COMMIT`)에만 한다. Transaction 안에서 먼저 지우면 이후 Rollback 때 게시글은 남고 이미지만 사라질 수 있기 때문이다.
- 저장소 삭제와 기록 삭제는 새 Transaction(`REQUIRES_NEW`)에서 한다. 기록 삭제는 `DELETE_PENDING` 상태일 때만 한다.

### 정리 스케줄러

`PostImageCleanupScheduler`가 `cking.post.image-cleanup-interval-ms`(기본 10분)마다 실행한다.

1. 24시간이 지나도록 연결되지 않은 `UPLOADING`·`UPLOADED` 기록을 `post_id IS NULL` 조건부 UPDATE로 `DELETE_PENDING`으로 선점한다. 같은 이미지를 게시글에 연결하는 요청과 경합해도 한쪽만 성공하므로, 연결된 이미지는 지워지지 않는다.
2. `DELETE_PENDING`이 된 지 10분이 지난 기록을 최대 100건씩 저장소에서 지우고 기록을 삭제한다. 유예 시간은 방금 Commit된 수정·삭제의 AFTER_COMMIT 삭제와 겹치지 않게 하기 위함이다. 실패한 기록은 `status_changed_at`을 현재 시각으로 미뤄 대기열 뒤로 보내고 유예 시간 뒤 다시 시도한다. 계속 실패하는 기록이 앞 100건을 차지해 뒤의 기록이 처리되지 않는 일을 막기 위함이다. 저장소 delete는 없는 key도 성공하므로 여러 번 실행돼도 안전하다.

임시 저장 기능이 생기면 미연결 보관 시간(24시간)을 다시 정한다.
