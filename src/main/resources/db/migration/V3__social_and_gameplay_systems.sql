-- ============================================================
-- V3 标准网游系统矩阵：社交 / 聊天 / 签到 / 公告 / 公会 / 任务
-- 全部带 version 乐观锁与必要索引；金额类用 BIGINT，计数类用 INT
-- ============================================================

-- ---------- 好友申请 ----------
CREATE TABLE IF NOT EXISTS t_friend_request (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    from_id    BIGINT      NOT NULL,
    to_id      BIGINT      NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/REJECTED',
    version    INT         NOT NULL DEFAULT 0,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_friend_request (from_id, to_id),
    KEY idx_friend_request_to (to_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '好友申请';

-- ---------- 聊天消息 ----------
CREATE TABLE IF NOT EXISTS t_chat_message (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    channel    VARCHAR(16)  NOT NULL COMMENT 'WORLD/GUILD/PRIVATE',
    from_id    BIGINT       NOT NULL,
    from_name  VARCHAR(64)  NOT NULL,
    to_id      BIGINT       NULL COMMENT 'PRIVATE 时的接收者；WORLD/GUILD 为 NULL',
    content    VARCHAR(500) NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_chat_channel_created (channel, created_at),
    KEY idx_chat_private (to_id, from_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '聊天消息';

-- ---------- 签到 ----------
CREATE TABLE IF NOT EXISTS t_sign_in (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    user_id       BIGINT   NOT NULL,
    sign_date     DATE     NOT NULL,
    streak        INT      NOT NULL DEFAULT 1 COMMENT '连续签到天数',
    reward_gold   BIGINT   NOT NULL DEFAULT 0,
    version       INT      NOT NULL DEFAULT 0,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sign_in_user_date (user_id, sign_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '每日签到';

-- ---------- 公告 ----------
CREATE TABLE IF NOT EXISTS t_announcement (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    title      VARCHAR(128) NOT NULL,
    content    VARCHAR(2000) NOT NULL,
    active     TINYINT      NOT NULL DEFAULT 1,
    start_at   DATETIME     NULL,
    end_at     DATETIME     NULL,
    version    INT          NOT NULL DEFAULT 0,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_announcement_active (active, start_at, end_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统公告';

-- ---------- 公会 ----------
CREATE TABLE IF NOT EXISTS t_guild (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    name         VARCHAR(32)  NOT NULL,
    leader_id    BIGINT       NOT NULL,
    level        INT          NOT NULL DEFAULT 1,
    exp          BIGINT       NOT NULL DEFAULT 0,
    notice       VARCHAR(500) NOT NULL DEFAULT '',
    member_count INT          NOT NULL DEFAULT 1,
    max_members  INT          NOT NULL DEFAULT 50,
    version      INT          NOT NULL DEFAULT 0,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_guild_name (name),
    KEY idx_guild_level (level)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '公会';

CREATE TABLE IF NOT EXISTS t_guild_member (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    guild_id  BIGINT      NOT NULL,
    user_id   BIGINT      NOT NULL,
    position  VARCHAR(16) NOT NULL DEFAULT 'MEMBER' COMMENT 'LEADER/OFFICER/MEMBER',
    version   INT         NOT NULL DEFAULT 0,
    joined_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_guild_member_user (user_id),
    KEY idx_guild_member_guild (guild_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '公会成员';

-- ---------- 任务 / 成就 ----------
CREATE TABLE IF NOT EXISTS t_quest (
    id                  INT          NOT NULL AUTO_INCREMENT,
    code                VARCHAR(64)  NOT NULL COMMENT '业务码，代码里引用',
    name                VARCHAR(64)  NOT NULL,
    type                VARCHAR(32)  NOT NULL COMMENT 'DAILY/ACHIEVEMENT',
    target_count        INT          NOT NULL COMMENT '目标次数',
    reward_gold         BIGINT       NOT NULL DEFAULT 0,
    reward_item_id      INT          NULL,
    reward_item_amount  INT          NOT NULL DEFAULT 0,
    version             INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quest_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '任务/成就定义';

CREATE TABLE IF NOT EXISTS t_quest_progress (
    id             BIGINT   NOT NULL AUTO_INCREMENT,
    user_id        BIGINT   NOT NULL,
    quest_id       INT      NOT NULL,
    progress       INT      NOT NULL DEFAULT 0,
    completed      TINYINT  NOT NULL DEFAULT 0,
    reward_claimed TINYINT  NOT NULL DEFAULT 0,
    version        INT      NOT NULL DEFAULT 0,
    updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quest_progress (user_id, quest_id),
    KEY idx_quest_progress_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '任务进度';
