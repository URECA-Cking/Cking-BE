CREATE TABLE admin_account (
    admin_account_id BIGINT       NOT NULL AUTO_INCREMENT,
    member_id        BIGINT       NOT NULL,
    login_id         VARCHAR(100) NOT NULL,
    password_hash    VARCHAR(100) NOT NULL,
    active           BOOLEAN      NOT NULL,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (admin_account_id),
    CONSTRAINT uk_admin_account_member UNIQUE (member_id),
    CONSTRAINT uk_admin_account_login_id UNIQUE (login_id),
    CONSTRAINT fk_admin_account_member FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
