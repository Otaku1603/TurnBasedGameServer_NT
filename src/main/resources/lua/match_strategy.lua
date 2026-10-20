-- ============================================================
-- 匹配策略（热更脚本）——由 MatchService.dynamicRange 调用
--
-- 职责：按玩家在队列中的等待秒数，返回其 Elo 匹配区间宽度。
-- 修改本函数即可调整匹配手感（扩大/收缩/分段策略），无需重启服务。
--
-- 注意：本沙箱已剥离 os/io/package 等危险全局，纯计算用途。
-- ============================================================

-- 参数（可按运营需要直接改数值后经管理端热更）
local BASE_RANGE = 200        -- 刚入队的匹配区间
local GROWTH_PER_10S = 100    -- 每等待 10 秒扩大的幅度
local MAX_RANGE = 500         -- 区间上限

function match_range(waited_seconds)
    local extra = math.floor(waited_seconds / 10) * GROWTH_PER_10S
    local range = BASE_RANGE + extra
    if range > MAX_RANGE then
        range = MAX_RANGE
    end
    return range
end
