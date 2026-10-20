/**
 * 风控模块。
 *
 * <p>定位：审计与拦截，不做"自动惩罚判决"——封禁决策留给 GM（人工）或后续策略引擎。
 * <ul>
 *   <li><b>硬限流</b>：战斗操作 / 匹配加入 / 登录失败按滑动窗口硬拦（超限抛 RATE_LIMITED）。</li>
 *   <li><b>异常检测</b>：机器行为（操作节奏人类不可能）、登录爆破，写入 t_risk_event。</li>
 *   <li><b>GM 处置</b>：admin 模块读取事件列表并处置（忽略 / 标记已处理 / 顺带封禁）。</li>
 * </ul>
 */
@ApplicationModule(displayName = "risk")
package com.game.nt.risk;

import org.springframework.modulith.ApplicationModule;
