-- Creator Space 배너 이미지 업로드(이슈 #590). 프로필 사진(V67)과 같은 방식으로 업로드 이미지 키와 기본 배너 URL을 둔다.
ALTER TABLE creator_space
    ADD COLUMN banner_image_key VARCHAR(200) NULL AFTER banner_image_url,
    ADD COLUMN default_banner_image_url VARCHAR(500) NULL AFTER banner_image_key;

UPDATE creator_space
SET default_banner_image_url = banner_image_url;

ALTER TABLE creator_space
    MODIFY COLUMN default_banner_image_url VARCHAR(500) NOT NULL;

-- 배너 이미지도 프로필 사진과 같은 테이블·같은 정리 작업을 쓴다. 종류를 구분해 프로필 키를 배너로(또는 반대로) 연결하지 못하게 한다.
ALTER TABLE creator_space_profile_image
    ADD COLUMN image_kind VARCHAR(20) NOT NULL DEFAULT 'PROFILE' AFTER creator_id;
