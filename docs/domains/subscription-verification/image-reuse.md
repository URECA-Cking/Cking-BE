# 구독 인증 이미지 재사용 탐지

SUB-15는 정규화된 구독 인증 이미지의 SHA-256 exact match만 탐지·기록한다. 재사용 탐지는 어뷰징 운영 신호이며, 서버 판정이나 보상 정책이 아니다.

## 범위와 비범위

- `SubscriptionImageProcessor`가 만든 정규화 JPEG의 `imageSha256`만 비교한다.
- pHash, dHash, OCR, 이미지 유사도 비교는 수행하지 않는다.
- 이미지 bytes, Base64, Object key는 탐지 결과나 로그에 기록하지 않는다.
- hash 일치만으로 `REJECTED`로 전이하거나 사용자를 차단하지 않는다.
- VLM 판정, `SubscriptionVerificationDecisionPolicy`, Ticket ONCE 적립은 탐지 결과를 읽거나 변경하지 않는다.

## 기록과 동시성

Verification 저장 Transaction은 엔티티 시각 생성과 저장 전에 hash 잠금 행을 `INSERT IGNORE`로 만들고 `FOR UPDATE`로 잠근다. 잠금 보유 중 현재 행을 제외한 같은 hash 이력을 `created_at ASC, verification_id ASC` 순으로 한 행만 조회해 매칭 대상으로 선택한다. 따라서 최초·재사용 판정의 순서는 시계나 식별자 발급 순서가 아니라 hash 잠금 획득 순서를 따른다.

| `reuseType` | 의미 | `matchedVerificationId` |
| --- | --- | --- |
| `FIRST_USE` | 이전 동일 hash가 없는 최초 제출 | `null` |
| `SAME_MEMBER_SAME_CREATOR` | 같은 사용자·같은 Creator의 재사용 | 이전 Verification ID |
| `SAME_MEMBER_DIFFERENT_CREATOR` | 같은 사용자·다른 Creator의 재사용 | 이전 Verification ID |
| `DIFFERENT_MEMBER` | 다른 사용자의 재사용 | 이전 Verification ID |

hash 잠금은 동시에 같은 이미지를 제출한 Transaction도 하나만 `FIRST_USE`로 기록하게 한다. MySQL의 잠금 교착으로 획득에 실패한 제출 저장은 이미 업로드한 Object를 재사용해 제한적으로 다시 시도한다. 탐지 결과는 Verification당 한 행으로, 유형·이전 Verification ID·탐지 시각만 보존한다.

## 운영 조회

관리자는 `GET /api/admin/subscription-verifications/{verificationId}/image-reuse`로 결과를 조회한다. 응답에는 현재 Verification ID, 이전 매칭 ID, `reuseType`, `detectedAt`만 포함하며 hash·이미지 URL·이미지 bytes는 반환하지 않는다.

향후 재사용 신호를 자동 심사 정책에 연결하려면 별도 합의와 상태 전이·보상 계약 변경이 필요하다.
