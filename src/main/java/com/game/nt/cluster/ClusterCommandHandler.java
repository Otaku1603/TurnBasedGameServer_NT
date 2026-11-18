package com.game.nt.cluster;

/**
 * 跨节点指令处理器（由 gateway 实现并注册为 Bean，cluster 负责分发）。
 */
public interface ClusterCommandHandler {

    void onCommand(ClusterCommand command);
}
