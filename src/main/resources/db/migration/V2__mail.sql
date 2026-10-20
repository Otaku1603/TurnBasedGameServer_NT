-- ============================================================
-- V2 邮件系统
--
-- 设计要点：
--   1. 邮件与附件分表：一封邮件可带多个附件（金币/道具）
--   2. 附件单独标记 claimed：支持"部分领取"语义，且领取操作可幂等
--   3. 收件箱查询走 (receiver_id, status, created_at) 复合索引
--   4. 过期清理走 expire_at 索引（不物理删除，仅逻辑过期）
-- ============================================================

CREATE TABLE IF NOT EXISTS t_mail (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    receiver_id BIGINT       NOT NULL COMMENT '收件人',
    sender_type VARCHAR(16)  NOT NULL COMMENT 'SYSTEM / PLAYER',
    sender_id   BIGINT       NULL COMMENT '玩家发件时的发件人 ID；系统邮件为 NULL',
    sender_name VARCHAR(64)  NOT NULL DEFAULT '系统',
    title       VARCHAR(128) NOT NULL,
    content     VARCHAR(1000) NOT NULL DEFAULT '',
    is_read     TINYINT      NOT NULL DEFAULT 0 COMMENT '0=未读 1=已读',
    is_deleted  TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除（玩家删除）',
    expire_at   DATETIME     NOT NULL COMMENT '过期时间',
    version     INT          NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_mail_receiver (receiver_id, is_deleted, is_read, created_at),
    KEY idx_mail_expire (expire_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '邮件';

CREATE TABLE IF NOT EXISTS t_mail_attachment (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    mail_id   BIGINT      NOT NULL,
    type      VARCHAR(16) NOT NULL COMMENT 'GOLD / ITEM',
    item_id   INT         NOT NULL DEFAULT 0 COMMENT 'ITEM 时的道具 ID；GOLD 填 0',
    amount    INT         NOT NULL COMMENT '数量',
    claimed   TINYINT     NOT NULL DEFAULT 0 COMMENT '0=未领取 1=已领取',
    PRIMARY KEY (id),
    UNIQUE KEY uk_mail_attachment (mail_id, type, item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '邮件附件';
