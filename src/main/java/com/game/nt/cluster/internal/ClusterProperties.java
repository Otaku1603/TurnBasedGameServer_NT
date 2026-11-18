package com.game.nt.cluster.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 集群配置。
 *
 * @param nodeId             节点 ID（部署必须显式且唯一、稳定；缺省仅本地开发兜底）
 * @param routeTtlHours      用户路由 TTL（登录时写入、断开时删除，TTL 兜底异常掉线残留）
 * @param battleTtlHours     战斗归属 TTL
 * @param nodeTtlSeconds     节点存活 TTL（心跳周期 1/3，失联即视为下线）
 * @param heartbeatIntervalMs 节点心跳间隔（与 application.yml 的调度占位符保持一致）
 */
@ConfigurationProperties(prefix = "app.cluster")
public record ClusterProperties(
        String nodeId,
        long routeTtlHours,
        long battleTtlHours,
        long nodeTtlSeconds,
        long heartbeatIntervalMs) {

    public ClusterProperties {
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = "node-" + java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        }
        if (routeTtlHours <= 0) {
            routeTtlHours = 6;
        }
        if (battleTtlHours <= 0) {
            battleTtlHours = 24;
        }
        if (nodeTtlSeconds <= 0) {
            nodeTtlSeconds = 30;
        }
        if (heartbeatIntervalMs <= 0) {
            heartbeatIntervalMs = 10_000;
        }
    }
}
