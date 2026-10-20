-- ============================================================
-- 战斗数值公式（Lua 热更新脚本）
--
-- 设计约束：
--   1. 纯函数：不依赖 os / io / 时间 / 全局可变状态 —— 保证「相同输入必得相同输出」，
--      配合服务端记录的事件流即可确定性回放。
--   2. 随机性由 Java 侧以参数 rng 传入（[0,1)），脚本自身不调用 math.random，
--      因此随机源可被记录与复现（防作弊 + 可回放）。
--   3. 沙箱环境已剥离 os/io/package/debug 等危险全局（见 LuaSandbox）。
--
-- 修改本文件后可通过管理端热更新，无需重启服务。
-- ============================================================

-- 通用工具：区间裁剪
local function clamp(v, lo, hi)
    if v < lo then return lo end
    if v > hi then return hi end
    return v
end

-- 伤害计算
--   attack      : 攻击方攻击力
--   defense     : 防御方防御力
--   skill_mult  : 技能倍率（百分比，100 表示 1.0 倍）
--   is_crit     : 是否暴击（1/0）
--   defending   : 防御方是否处于防御姿态（1/0）
--   rng         : [0,1) 随机数（用于 ±10% 浮动），由 Java 传入
-- 返回：最终伤害（整数，至少 1）
function calc_damage(attack, defense, skill_mult, is_crit, defending, rng)
    -- 减伤公式：防御的边际收益递减，避免高防无敌
    local mitigation = defense / (defense + 200.0)
    local base = attack * (skill_mult / 100.0) * (1.0 - mitigation)

    -- 暴击 ×1.5
    if is_crit == 1 then
        base = base * 1.5
    end

    -- 防御姿态减伤 50%
    if defending == 1 then
        base = base * 0.5
    end

    -- ±10% 浮动
    local float = 0.9 + (rng * 0.2)
    base = base * float

    local dmg = math.floor(base)
    if dmg < 1 then dmg = 1 end
    return dmg
end

-- 治疗计算
--   caster_attack : 施法者攻击力（作为治疗强度基准）
--   skill_mult    : 技能倍率（百分比）
--   rng           : [0,1) 随机数
-- 返回：治疗量（整数，至少 1）
function calc_heal(caster_attack, skill_mult, rng)
    local base = caster_attack * (skill_mult / 100.0) * (0.95 + rng * 0.1)
    local heal = math.floor(base)
    if heal < 1 then heal = 1 end
    return heal
end

-- 闪避判定
--   dodge_rate : 闪避率（百分比，0-100）
--   rng        : [0,1) 随机数
-- 返回：1 = 闪避成功，0 = 未闪避
function calc_dodge(dodge_rate, rng)
    local rate = clamp(dodge_rate, 0, 95) / 100.0
    if rng < rate then
        return 1
    end
    return 0
end
