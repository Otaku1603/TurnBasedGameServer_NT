package com.game.nt.risk;

import java.util.List;

/**
 * 风控能力（模块 API）。
 *
 * <p>调用约定：
 * <ul>
 *   <li>限流钩子在"动作发生前"调用，超限直接抛 {@code BusinessException(RATE_LIMITED)}；</li>
 *   <li>检测钩子在动作发生时调用，发现异常落 {@code t_risk_event}（不阻断业务）。</li>
 * </ul>
 */
public interface RiskApi {

    /** 战斗操作限流 + 机器节奏检测（BattleActionHandler 每次操作调用）。 */
    void onBattleAction(long userId);

    /** 匹配加入限流（MatchHandler.join 调用）。 */
    void onMatchJoin(long userId);

    /** 登录失败：按用户 + IP 限流并记录爆破事件（LoginHandler/IdentityService 调用）。 */
    void onLoginFailure(String username, String ip);

    /** 记录一条风控事件（供其他模块的自定义检测复用）。 */
    void recordEvent(String type, Long userId, String severity, String detail);

    // ---------- GM 查询/处置（admin 模块使用） ----------

    List<RiskEventView> listEvents(String status, int page, int size);

    long countEvents(String status);

    /** 处置事件：status = RESOLVED / DISMISSED。 */
    void resolveEvent(long eventId, String status, long adminId);

    /** 事件视图。 */
    record RiskEventView(long id, Long userId, String type, String severity,
                         String detail, String status, java.time.LocalDateTime createdAt) {
    }
}
