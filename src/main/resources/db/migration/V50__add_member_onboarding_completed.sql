-- 온보딩 완료 여부 (신규 가입자 자동 이동 판별)
--
-- 배포 이전에 가입한 회원은 이미 서비스를 쓰고 있으므로 전원 완료(TRUE)로 채운다.
-- 이후 생성되는 회원은 기본값 FALSE로 시작한다.
ALTER TABLE member ADD COLUMN onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE member SET onboarding_completed = TRUE;
