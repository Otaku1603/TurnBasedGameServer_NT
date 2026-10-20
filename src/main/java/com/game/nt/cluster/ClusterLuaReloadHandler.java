package com.game.nt.cluster;

/**
 * 集群 Lua 热更处理器（由 admin 实现，cluster 负责分发）。
 *
 * <p>与 {@link ClusterCommandHandler} 同构：cluster 只定义接口、不反向依赖业务模块，
 * 具体"热更哪个沙箱"由 admin 决定——保持 cluster 的通用性。
 */
public interface ClusterLuaReloadHandler {

    /**
     * 在本节点应用一次 Lua 热更。
     *
     * @param target combat=战斗公式 / match=匹配策略（默认 combat）
     * @param script 新脚本内容；null/空白 = 从 classpath 重新加载
     */
    void onLuaReload(String target, String script);
}
