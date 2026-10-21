-- 0=도입 이전/제외, 1=미처리, 2=완료. 기존 지급 필드는 변경하지 않는다.
-- MySQL 원자적 DDL 단위로 재진입한다. 실패한 Flyway 이력의 repair는 운영자만 수행한다.
SET @cking_reward_notice_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ticket_ledger' AND column_name='reward_notification_state'), 'DO 0', 'ALTER TABLE ticket_ledger
    ADD COLUMN reward_notification_state TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN reward_notification_retry_at DATETIME(6) NOT NULL DEFAULT ''1970-01-01 00:00:00'',
    ADD COLUMN reward_notification_id BIGINT NULL,
    ALGORITHM=INSTANT');
PREPARE cking_reward_notice_stmt FROM @cking_reward_notice_ddl;
EXECUTE cking_reward_notice_stmt;
DEALLOCATE PREPARE cking_reward_notice_stmt;

SET @cking_reward_notice_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='common_ticket_ledger' AND column_name='reward_notification_state'), 'DO 0', 'ALTER TABLE common_ticket_ledger
    ADD COLUMN reward_notification_state TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN reward_notification_retry_at DATETIME(6) NOT NULL DEFAULT ''1970-01-01 00:00:00'',
    ADD COLUMN reward_notification_id BIGINT NULL,
    ALGORITHM=INSTANT');
PREPARE cking_reward_notice_stmt FROM @cking_reward_notice_ddl;
EXECUTE cking_reward_notice_stmt;
DEALLOCATE PREPARE cking_reward_notice_stmt;

SET @cking_reward_notice_ddl = IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='ticket_ledger' AND constraint_name='ck_reward_creator_state'), 'DO 0', 'ALTER TABLE ticket_ledger
    ADD CONSTRAINT uk_reward_creator_owner UNIQUE (ledger_id, member_id),
    ADD INDEX idx_reward_creator_due (reward_notification_state, type, reward_notification_retry_at, ledger_id),
    ADD CONSTRAINT ck_reward_creator_state CHECK (
        reward_notification_state IN (0,1,2) AND (
            (reward_notification_state=2 AND reward_notification_id IS NOT NULL) OR
            (reward_notification_state<>2 AND reward_notification_id IS NULL)))');
PREPARE cking_reward_notice_stmt FROM @cking_reward_notice_ddl;
EXECUTE cking_reward_notice_stmt;
DEALLOCATE PREPARE cking_reward_notice_stmt;

SET @cking_reward_notice_ddl = IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='common_ticket_ledger' AND constraint_name='ck_reward_common_state'), 'DO 0', 'ALTER TABLE common_ticket_ledger
    ADD CONSTRAINT uk_reward_common_owner UNIQUE (ledger_id, member_id),
    ADD INDEX idx_reward_common_due (reward_notification_state, type, reward_notification_retry_at, ledger_id),
    ADD CONSTRAINT ck_reward_common_state CHECK (
        reward_notification_state IN (0,1,2) AND (
            (reward_notification_state=2 AND reward_notification_id IS NOT NULL) OR
            (reward_notification_state<>2 AND reward_notification_id IS NULL)))');
PREPARE cking_reward_notice_stmt FROM @cking_reward_notice_ddl;
EXECUTE cking_reward_notice_stmt;
DEALLOCATE PREPARE cking_reward_notice_stmt;

-- 지급 알림 참조
SET @cking_reward_notice_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='notification' AND column_name='ticket_ledger_id'), 'DO 0', 'ALTER TABLE notification
    ADD COLUMN ticket_ledger_id BIGINT NULL,
    ADD COLUMN common_ticket_ledger_id BIGINT NULL,
    ALGORITHM=INSTANT');
PREPARE cking_reward_notice_stmt FROM @cking_reward_notice_ddl;
EXECUTE cking_reward_notice_stmt;
DEALLOCATE PREPARE cking_reward_notice_stmt;

SET @cking_reward_notice_ddl = IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='notification' AND constraint_name='ck_notification_reward_source'), 'DO 0', 'ALTER TABLE notification
    ADD CONSTRAINT uk_notification_ticket_ledger UNIQUE (ticket_ledger_id),
    ADD CONSTRAINT uk_notification_common_ledger UNIQUE (common_ticket_ledger_id),
    ADD CONSTRAINT uk_notification_creator_ref UNIQUE (id, ticket_ledger_id, member_id),
    ADD CONSTRAINT uk_notification_common_ref UNIQUE (id, common_ticket_ledger_id, member_id),
    ADD CONSTRAINT fk_notification_ticket_ledger FOREIGN KEY (ticket_ledger_id, member_id)
        REFERENCES ticket_ledger (ledger_id, member_id),
    ADD CONSTRAINT fk_notification_common_ledger FOREIGN KEY (common_ticket_ledger_id, member_id)
        REFERENCES common_ticket_ledger (ledger_id, member_id),
    ADD CONSTRAINT ck_notification_reward_source CHECK (
        (type=''COMMON_TICKET_REWARDED'' AND common_ticket_ledger_id IS NOT NULL AND ticket_ledger_id IS NULL
            AND event_id IS NULL AND drawing_id IS NULL AND winner_id IS NULL AND sanction_id IS NULL) OR
        (type=''CREATOR_TICKET_REWARDED'' AND ticket_ledger_id IS NOT NULL AND common_ticket_ledger_id IS NULL
            AND event_id IS NULL AND drawing_id IS NULL AND winner_id IS NULL AND sanction_id IS NULL) OR
        (type NOT IN (''COMMON_TICKET_REWARDED'',''CREATOR_TICKET_REWARDED'')
            AND ticket_ledger_id IS NULL AND common_ticket_ledger_id IS NULL))');
PREPARE cking_reward_notice_stmt FROM @cking_reward_notice_ddl;
EXECUTE cking_reward_notice_stmt;
DEALLOCATE PREPARE cking_reward_notice_stmt;

SET @cking_reward_notice_ddl = IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='ticket_ledger' AND constraint_name='fk_reward_creator_notification'), 'DO 0', 'ALTER TABLE ticket_ledger
    ADD CONSTRAINT fk_reward_creator_notification FOREIGN KEY (reward_notification_id, ledger_id, member_id)
        REFERENCES notification (id, ticket_ledger_id, member_id)');
PREPARE cking_reward_notice_stmt FROM @cking_reward_notice_ddl;
EXECUTE cking_reward_notice_stmt;
DEALLOCATE PREPARE cking_reward_notice_stmt;

SET @cking_reward_notice_ddl = IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='common_ticket_ledger' AND constraint_name='fk_reward_common_notification'), 'DO 0', 'ALTER TABLE common_ticket_ledger
    ADD CONSTRAINT fk_reward_common_notification FOREIGN KEY (reward_notification_id, ledger_id, member_id)
        REFERENCES notification (id, common_ticket_ledger_id, member_id)');
PREPARE cking_reward_notice_stmt FROM @cking_reward_notice_ddl;
EXECUTE cking_reward_notice_stmt;
DEALLOCATE PREPARE cking_reward_notice_stmt;

-- 재시도 시각 기본값을 먼저 바꾼 후 원장별 state 기본값 전환을 활성화 경계로 삼는다.
ALTER TABLE ticket_ledger ALTER COLUMN reward_notification_retry_at SET DEFAULT (UTC_TIMESTAMP(6)), ALGORITHM=INSTANT;

ALTER TABLE common_ticket_ledger ALTER COLUMN reward_notification_retry_at SET DEFAULT (UTC_TIMESTAMP(6)), ALGORITHM=INSTANT;

ALTER TABLE ticket_ledger ALTER COLUMN reward_notification_state SET DEFAULT 1, ALGORITHM=INSTANT;

ALTER TABLE common_ticket_ledger ALTER COLUMN reward_notification_state SET DEFAULT 1, ALGORITHM=INSTANT;

