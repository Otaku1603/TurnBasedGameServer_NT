/**
 * 集群/跨服基建（A9）。
 *
 * <p>核心机制：
 * <ul>
 *   <li><b>节点注册</b>：每节点以 TTL key 声明存活（心跳续期），支撑"双实例"演示与 GM 集群视图。</li>
 *   <li><b>会话路由</b>：登录时写 {@code cluster:route:{userId} -> nodeId}，
 *       推送先查本地会话、未命中按路由经 Redis Pub/Sub 转发到归属节点。</li>
 *   <li><b>战斗归属</b>：battleId 创建时写 {@code cluster:battle:{battleId} -> nodeId}，
 *       非归属节点的战斗指令原样转发到归属节点执行（战斗状态仍只在单节点单线程，不引入分布式战斗状态）。</li>
 * </ul>
 *
 * <p>依赖方向：gateway / battle / match / admin → cluster（单向）；cluster 只依赖 shared 与 Redis。
 */
@ApplicationModule(displayName = "cluster")
package com.game.nt.cluster;

import org.springframework.modulith.ApplicationModule;
