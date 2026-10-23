-- 원본 Seed 결정적 재현 당첨자를 검증 이력과 함께 보존한다.
-- 기존 이력과 재현 단계에 도달하지 못한 실패는 NULL로 구분한다.
ALTER TABLE draw_verification_history
    ADD COLUMN replayed_winners JSON NULL AFTER ranks_matched;
