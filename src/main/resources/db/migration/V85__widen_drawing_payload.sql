-- 추첨 입력은 후보마다 한 줄이라 후보가 약 9천 명을 넘으면 TEXT(65,535바이트)를 넘는다.
ALTER TABLE drawing
    MODIFY COLUMN input_payload  MEDIUMTEXT NULL,
    MODIFY COLUMN output_payload MEDIUMTEXT NULL;
