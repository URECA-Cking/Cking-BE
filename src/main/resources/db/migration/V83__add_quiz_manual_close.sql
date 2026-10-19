-- 기존 Quiz의 상태와 게시 포인터·최초 게시 고정은 변경하지 않는다.
ALTER TABLE quiz
    ADD COLUMN closed_at DATETIME(6) NULL AFTER unpublished_at,
    DROP CHECK ck_quiz_publication_status,
    DROP CHECK ck_quiz_publication,
    ADD CONSTRAINT ck_quiz_publication_status
        CHECK (publication_status IN ('NOT_PUBLISHED', 'PUBLISHED', 'UNPUBLISHED', 'CLOSED')),
    ADD CONSTRAINT ck_quiz_publication CHECK (
        (publication_status = 'PUBLISHED' AND published_revision_id IS NOT NULL)
        OR (publication_status IN ('NOT_PUBLISHED', 'UNPUBLISHED') AND published_revision_id IS NULL)
        OR publication_status = 'CLOSED'
    ),
    ADD CONSTRAINT ck_quiz_closed_at
        CHECK ((publication_status = 'CLOSED') = (closed_at IS NOT NULL)),
    ADD CONSTRAINT ck_quiz_closed_lock
        CHECK (publication_status <> 'CLOSED' OR content_locked = TRUE);
