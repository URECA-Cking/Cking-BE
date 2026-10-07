-- 내용 지문을 적용 실행의 멱등 키와 분리한다. 기존 이력은 음수 예약 영역으로 보존한다.
ALTER TABLE creator_similarity_generation ADD COLUMN application_sequence BIGINT NULL;
UPDATE creator_similarity_generation SET application_sequence = -generation_id;
ALTER TABLE creator_similarity_generation
    MODIFY application_sequence BIGINT NOT NULL,
    DROP INDEX uk_creator_similarity_generation_input,
    ADD CONSTRAINT uk_creator_similarity_generation_sequence UNIQUE (creator_id, application_sequence);

ALTER TABLE interest_recommendation_generation ADD COLUMN application_sequence BIGINT NULL;
UPDATE interest_recommendation_generation SET application_sequence = -generation_id;
ALTER TABLE interest_recommendation_generation
    MODIFY application_sequence BIGINT NOT NULL,
    DROP INDEX uk_interest_recommendation_generation_input,
    ADD CONSTRAINT uk_interest_recommendation_generation_sequence
        UNIQUE (taxonomy_version, interest_code, application_sequence);
