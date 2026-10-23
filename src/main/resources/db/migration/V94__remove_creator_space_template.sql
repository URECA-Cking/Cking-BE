-- Creator Space 템플릿 기능 제거(이슈 #754). 승인 시 기본 소개 문구와 이미지 없음으로 Space를 만들고, 이미지가 없으면 화면이 기본 이미지를 보여 준다.
-- 이미 만든 Space는 템플릿에서 복사한 이미지 URL을 그대로 갖는다. 템플릿 이미지 업로드 기록을 지우면 정리 작업도 없어지므로,
-- 템플릿용으로 올린 저장소 객체(creator-space-profile-images/template/)는 더 이상 지우지 않고 남는다.
ALTER TABLE creator_space
    MODIFY COLUMN profile_image_url VARCHAR(500) NULL,
    MODIFY COLUMN default_profile_image_url VARCHAR(500) NULL,
    MODIFY COLUMN banner_image_url VARCHAR(500) NULL,
    MODIFY COLUMN default_banner_image_url VARCHAR(500) NULL;

DROP TABLE creator_space_template_image;
DROP TABLE creator_space_template;
