-- ============================================================
-- V1 初始化 schema（TurnBasedGame_Server_NT）
--
-- 相对原项目 init.sql 的修正：
--   1. 去掉重复索引（原 t_user 同时 UNIQUE(username) + INDEX(username)）
--   2. 所有可变表加 version 列（乐观锁）与 updated_at
--   3. battle_log_json 由 TEXT(64KB) 改为 LONGTEXT（长局会超限）
--   4. 新增 t_idempotency（幂等键）与 t_outbox（事务性发件箱）
--   5. 统一字符集 utf8mb4；金额/数值字段类型收敛
-- ============================================================

-- ---------- 用户 ----------
CREATE TABLE IF NOT EXISTS t_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL COMMENT '登录名',
    nickname      VARCHAR(64)  NOT NULL COMMENT '昵称',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希',
    elo_rating    INT          NOT NULL DEFAULT 1000,
    gold          BIGINT       NOT NULL DEFAULT 0 COMMENT '金币（避免溢出用 BIGINT）',
    win_count     INT          NOT NULL DEFAULT 0,
    total_battles INT          NOT NULL DEFAULT 0,
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常, 0=封禁',
    version       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户';

-- ---------- 管理员 ----------
CREATE TABLE IF NOT EXISTS t_admin (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    version       INT          NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '后台管理员';

-- ---------- 角色模板（策划数值） ----------
CREATE TABLE IF NOT EXISTS t_character_template (
    id             INT          NOT NULL AUTO_INCREMENT,
    char_type      VARCHAR(32)  NOT NULL COMMENT '职业标识：warrior/mage/rogue',
    display_name   VARCHAR(64)  NOT NULL,
    base_hp        INT          NOT NULL,
    base_attack    INT          NOT NULL,
    base_defense   INT          NOT NULL,
    base_crit_rate INT          NOT NULL DEFAULT 5 COMMENT '暴击率(百分比)',
    base_dodge_rate INT         NOT NULL DEFAULT 5 COMMENT '闪避率(百分比)',
    version        INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_char_template_type (char_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色模板';

-- ---------- 角色 ----------
CREATE TABLE IF NOT EXISTS t_character (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    char_name   VARCHAR(64) NOT NULL,
    char_type   VARCHAR(32) NOT NULL,
    max_hp      INT         NOT NULL,
    attack      INT         NOT NULL,
    defense     INT         NOT NULL,
    crit_rate   INT         NOT NULL DEFAULT 5,
    dodge_rate  INT         NOT NULL DEFAULT 5,
    is_active   TINYINT     NOT NULL DEFAULT 1,
    version     INT         NOT NULL DEFAULT 0,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_character_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色';

-- ---------- 技能 ----------
CREATE TABLE IF NOT EXISTS t_skill (
    id          INT          NOT NULL AUTO_INCREMENT,
    skill_name  VARCHAR(64)  NOT NULL,
    skill_type  VARCHAR(32)  NOT NULL COMMENT 'attack/heal',
    char_type   VARCHAR(32)  NOT NULL COMMENT '所属职业',
    power       INT          NOT NULL DEFAULT 100 COMMENT '倍率(百分比)',
    cooldown    INT          NOT NULL DEFAULT 0 COMMENT '冷却回合',
    mp_cost     INT          NOT NULL DEFAULT 0 COMMENT '预留：蓝耗',
    target_type VARCHAR(32)  NOT NULL DEFAULT 'ENEMY' COMMENT 'ENEMY/SELF',
    version     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_skill_char_type (char_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '技能';

-- ---------- 角色-技能关联 ----------
CREATE TABLE IF NOT EXISTS t_character_skill (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    character_id BIGINT NOT NULL,
    skill_id     INT    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_char_skill (character_id, skill_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色已学技能';

-- ---------- 道具 ----------
CREATE TABLE IF NOT EXISTS t_item (
    id           INT         NOT NULL AUTO_INCREMENT,
    name         VARCHAR(64) NOT NULL,
    type         VARCHAR(32) NOT NULL COMMENT 'POTION/AVATAR_FRAME',
    price        INT         NOT NULL DEFAULT 0,
    effect_value INT         NOT NULL DEFAULT 0,
    version      INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '道具';

-- ---------- 背包 ----------
CREATE TABLE IF NOT EXISTS t_user_inventory (
    id         BIGINT  NOT NULL AUTO_INCREMENT,
    user_id    BIGINT  NOT NULL,
    item_id    INT     NOT NULL,
    count      INT     NOT NULL DEFAULT 0,
    is_equipped TINYINT NOT NULL DEFAULT 0,
    version    INT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_user_item (user_id, item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '背包';

-- ---------- 好友 ----------
CREATE TABLE IF NOT EXISTS t_friend (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    user_id    BIGINT   NOT NULL,
    friend_id  BIGINT   NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_friend_pair (user_id, friend_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '好友';

-- ---------- 留言板 ----------
CREATE TABLE IF NOT EXISTS t_message_board (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL COMMENT '被留言者',
    from_user_id BIGINT       NOT NULL COMMENT '留言者',
    content      VARCHAR(500) NOT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_board_user_created (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '留言板';

-- ---------- 战斗记录 ----------
CREATE TABLE IF NOT EXISTS t_battle_record (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    battle_id          VARCHAR(64) NOT NULL,
    player1_id         BIGINT      NOT NULL,
    player1_nickname   VARCHAR(64) NOT NULL,
    player1_char_id    BIGINT      NULL,
    player1_final_hp   INT         NOT NULL DEFAULT 0,
    player1_elo_before INT         NOT NULL DEFAULT 0,
    player1_elo_after  INT         NOT NULL DEFAULT 0,
    player2_id         BIGINT      NOT NULL,
    player2_nickname   VARCHAR(64) NOT NULL,
    player2_char_id    BIGINT      NULL,
    player2_final_hp   INT         NOT NULL DEFAULT 0,
    player2_elo_before INT         NOT NULL DEFAULT 0,
    player2_elo_after  INT         NOT NULL DEFAULT 0,
    winner_id          BIGINT      NOT NULL,
    end_reason         VARCHAR(32) NOT NULL,
    total_rounds       INT         NOT NULL DEFAULT 1,
    battle_duration    INT         NOT NULL DEFAULT 0 COMMENT '秒',
    battle_log_json    LONGTEXT    NULL COMMENT '完整事件日志（原为 TEXT，64KB 易截断）',
    start_time         DATETIME    NULL,
    end_time           DATETIME    NULL,
    created_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_battle_record_battle_id (battle_id),
    KEY idx_battle_record_winner (winner_id),
    KEY idx_battle_record_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '战斗记录';

-- ---------- 幂等键（业务原子化基础设施） ----------
CREATE TABLE IF NOT EXISTS t_idempotency (
    idem_key    VARCHAR(128) NOT NULL COMMENT '幂等键，如 settle:{battleId}',
    status      VARCHAR(16)  NOT NULL DEFAULT 'IN_PROGRESS' COMMENT 'IN_PROGRESS/DONE',
    result_ref  VARCHAR(128) NULL COMMENT '结果引用，便于排查',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (idem_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '幂等键';

-- ---------- 事务性发件箱（跨模块最终一致） ----------
CREATE TABLE IF NOT EXISTS t_outbox (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    event_id      VARCHAR(64)  NOT NULL,
    event_type    VARCHAR(128) NOT NULL,
    payload       TEXT         NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED',
    retry_count   INT          NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at  DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_status_created (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '事务性发件箱';
