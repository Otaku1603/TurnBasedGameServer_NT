-- A9/跨服：战斗归属与跨服标记 + 风控事件表
ALTER TABLE t_battle_record
    ADD COLUMN home_node VARCHAR(64) NULL COMMENT '战斗归属节点',
    ADD COLUMN is_cross_node TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否跨服对局';

CREATE TABLE t_risk_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NULL COMMENT '关联用户（系统级事件可为空）',
    type        VARCHAR(64) NOT NULL COMMENT '事件类型（如 ACTION_CADENCE/LOGIN_BURST）',
    severity    VARCHAR(16) NOT NULL COMMENT 'LOW/MEDIUM/HIGH',
    detail      VARCHAR(1024) NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/RESOLVED/DISMISSED',
    created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_risk_status (status, created_at),
    INDEX idx_risk_user (user_id)
) COMMENT '风控事件（异常行为审计与 GM 处置）';
