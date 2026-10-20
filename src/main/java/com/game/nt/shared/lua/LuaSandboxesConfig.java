package com.game.nt.shared.lua;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多脚本 Lua 沙箱装配。
 *
 * <p>主沙箱（战斗公式，{@code LuaSandbox} @Component，脚本 {@code lua/damage_formulas.lua}）
 * 之外，按职责拆分独立脚本与独立沙箱实例——脚本之间互不污染全局，可独立热更：
 * <ul>
 *   <li>{@code matchLuaSandbox}：匹配策略（{@code lua/match_strategy.lua}），由 MatchService 调用</li>
 * </ul>
 * 新增职责时在此追加 bean 并建对应脚本文件即可。
 */
@Configuration
public class LuaSandboxesConfig {

    @Bean(destroyMethod = "destroy")
    public LuaSandbox matchLuaSandbox() {
        LuaSandbox sandbox = new LuaSandbox("lua/match_strategy.lua");
        sandbox.init();
        return sandbox;
    }
}
