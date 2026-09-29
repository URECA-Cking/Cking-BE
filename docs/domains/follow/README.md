# Follow

사용자(Member)가 크리에이터(Creator)를 팔로우한 관계를 저장한다. 외부 API 계약은 [Follow API](api.md)를 따른다.

## 책임

- 팔로우·언팔로우와 팔로우 여부·내 팔로우 목록 조회를 제공한다.
- 다른 도메인은 `CreatorFollowQueryService.isFollowing(memberId, creatorId)`로 팔로우 여부를 확인한다. Creator Space 게시글의 팔로워 공개(FOLLOWERS) 권한 확인(#318)이 첫 사용처다.
- Creator 정보는 읽기만 한다. 팔로우 대상 존재 확인과 목록의 크리에이터 이름은 `CreatorRepository` 조회로 얻는다.

## 소유 데이터

`creator_follow` (V33): `member_id`, `creator_id`, `created_at`. 같은 사용자·크리에이터 쌍은 `uk_creator_follow_member_creator`로 하나만 존재한다.

## 불변조건

- Creator는 관리자 승인 시에만 생성되므로, 존재하는 Creator는 팔로우할 수 있다.
- 본인 Creator(Creator의 `member_id`가 호출자)는 팔로우할 수 없다.
- 팔로우는 unique 제약에 기대어 멱등하게 추가한다. `INSERT … ON DUPLICATE KEY UPDATE`로 중복 키만 무시하므로, 동시에 같은 요청이 들어와도 관계는 하나다. FK 위반 같은 다른 오류는 무시하지 않는다.
- 언팔로우는 행을 하드 삭제하며 이력을 남기지 않는다. 팔로우하지 않은 상태의 언팔로우도 성공한다.
- `isFollowing`은 비로그인(`memberId == null`)을 팔로우하지 않은 것으로 보며, Creator 존재 여부는 검증하지 않는다.
