-- ============================================================
-- V6 公会深度：入会申请 + 公会聊天
-- 仅新增表，不改动既有表结构（向后兼容）
-- ============================================================

-- ---------- 入会申请 ----------
CREATE TABLE IF NOT EXISTS t_guild_application (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    guild_id   BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
    version    INT         NOT NULL DEFAULT 0,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_guild_application (guild_id, user_id),
    KEY idx_guild_application_status (guild_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '入会申请';

-- ---------- 公会聊天消息 ----------
CREATE TABLE IF NOT EXISTS t_guild_message (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    guild_id   BIGINT       NOT NULL,
    from_id    BIGINT       NOT NULL,
    from_name  VARCHAR(64)  NOT NULL,
    content    VARCHAR(500) NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_guild_message_guild (guild_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '公会聊天消息';
