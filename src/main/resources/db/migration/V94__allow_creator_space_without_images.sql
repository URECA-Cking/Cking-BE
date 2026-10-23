-- Creator Space 템플릿 기능 제거 1단계(이슈 #754). 승인 시 기본 소개 문구와 이미지 없음으로 Space를 만들고, 이미지가 없으면 화면이 기본 이미지를 보여 준다.
-- 이미 만든 Space는 템플릿에서 복사한 이미지 URL을 그대로 갖는다.
-- 템플릿 테이블(creator_space_template, creator_space_template_image)은 이 배포에서 지우지 않는다. 롤링 배포 중 함께 도는 옛 버전 서버가
-- 템플릿 엔티티를 매핑하고 있어 테이블이 없으면 기동하지 못한다. 모든 서버가 이 버전이 된 뒤 다음 마이그레이션에서 지운다
-- (docs/operations/rolling-deploy.md).
ALTER TABLE creator_space
    MODIFY COLUMN profile_image_url VARCHAR(500) NULL,
    MODIFY COLUMN default_profile_image_url VARCHAR(500) NULL,
    MODIFY COLUMN banner_image_url VARCHAR(500) NULL,
    MODIFY COLUMN default_banner_image_url VARCHAR(500) NULL;
