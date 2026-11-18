-- ============================================================
-- V7 必需参考数据（与演示账号分离）
-- 只写职业/技能/道具/任务；管理员与演示玩家不在此创建。
-- 全部语句幂等，兼容已有同名数据的存量库。
-- ============================================================

-- ---------- 职业模板（唯一键 char_type） ----------
INSERT IGNORE INTO t_character_template
    (char_type, display_name, base_hp, base_attack, base_defense, base_crit_rate, base_dodge_rate)
VALUES
    ('warrior', '战士', 1200, 120, 90, 8, 5),
    ('mage',    '法师',  900, 160, 60, 10, 6),
    ('rogue',   '游侠', 1000, 135, 70, 15, 12);

-- ---------- 技能（按 skill_name 幂等；t_skill 无唯一键，用 NOT EXISTS） ----------
INSERT INTO t_skill (skill_name, skill_type, char_type, power, cooldown, mp_cost, target_type)
SELECT '普通攻击', 'attack', 'all', 100, 0, 0, 'ENEMY'
WHERE NOT EXISTS (SELECT 1 FROM t_skill WHERE skill_name = '普通攻击');

INSERT INTO t_skill (skill_name, skill_type, char_type, power, cooldown, mp_cost, target_type)
SELECT '重击', 'attack', 'warrior', 150, 2, 0, 'ENEMY'
WHERE NOT EXISTS (SELECT 1 FROM t_skill WHERE skill_name = '重击');

INSERT INTO t_skill (skill_name, skill_type, char_type, power, cooldown, mp_cost, target_type)
SELECT '治疗术', 'heal', 'mage', 120, 3, 0, 'SELF'
WHERE NOT EXISTS (SELECT 1 FROM t_skill WHERE skill_name = '治疗术');

INSERT INTO t_skill (skill_name, skill_type, char_type, power, cooldown, mp_cost, target_type)
SELECT '疾风斩', 'attack', 'rogue', 130, 1, 0, 'ENEMY'
WHERE NOT EXISTS (SELECT 1 FROM t_skill WHERE skill_name = '疾风斩');

-- ---------- 道具（按 name 幂等） ----------
INSERT INTO t_item (name, type, price, effect_value)
SELECT '初级治疗药水', 'POTION', 50, 200
WHERE NOT EXISTS (SELECT 1 FROM t_item WHERE name = '初级治疗药水');

INSERT INTO t_item (name, type, price, effect_value)
SELECT '头像框·青铜', 'AVATAR_FRAME', 200, 0
WHERE NOT EXISTS (SELECT 1 FROM t_item WHERE name = '头像框·青铜');

-- ---------- 任务/成就（唯一键 code） ----------
INSERT IGNORE INTO t_quest (code, name, type, target_count, reward_gold, reward_item_amount)
VALUES
    ('DAILY_SIGN_IN',   '每日签到', 'DAILY',       1,  50,   0),
    ('WIN_BATTLE',      '首场胜利', 'ACHIEVEMENT', 1,  200,  0),
    ('WIN_10_BATTLES',  '十胜成就', 'ACHIEVEMENT', 10, 1000, 0);

-- ---------- 存量角色补授全职业通用技能（幂等） ----------
INSERT IGNORE INTO t_character_skill (character_id, skill_id)
SELECT c.id, s.id
FROM t_character c
JOIN t_skill s ON s.char_type = 'all';
