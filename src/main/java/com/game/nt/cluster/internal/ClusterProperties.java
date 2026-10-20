package com.game.nt.cluster.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 集群配置。
 *
 * @param nodeId           节点 ID（默认取主机名 + 随机后缀，保证多实例不冲突）
 * @param routeTtlHours    用户路由 TTL（登录时写入、断开时删除，TTL 兜底异常掉线残留）
 * @param battleTtlHours   战斗归属 TTL
 * @param nodeTtlSeconds   节点存活 TTL（心跳周期 1/3，失联即视为下线）
 */
@ConfigurationProperties(prefix = "app.cluster")
public record ClusterProperties(
        String nodeId,
        long routeTtlHours,
        long battleTtlHours,
        long nodeTtlSeconds) {

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
    }
}
