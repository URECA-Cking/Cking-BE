-- Creator Space 커스텀 slug 변경 간격 제한 (이슈 #290)
--
-- Creator가 마지막으로 slug를 바꾼 시각(UTC)이다. NULL이면 승인 때 받은 자동 slug를 아직
-- 한 번도 바꾸지 않은 상태이며, 이때는 바로 바꿀 수 있다. 변경 후 14일 동안은 다시 바꿀 수 없다.
ALTER TABLE creator_space
    ADD COLUMN slug_changed_at DATETIME(6) NULL AFTER slug;
