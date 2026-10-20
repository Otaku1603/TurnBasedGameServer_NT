package com.game.nt.battle;

import org.springframework.modulith.events.Externalized;

/**
 * 战斗结算领域事件（事务性发件箱）。
 *
 * <p>结算事务内发布，由 Spring Modulith 事件发布注册表持久化到
 * {@code EVENT_PUBLICATION} 表；事务提交后异步派发给监听者，失败自动重试（at-least-once）。
 * 监听者侧必须以 {@code battleId} 做幂等兜底。
 *
 * <p>{@code @Externalized}：进程内监听器处理<b>且</b> Kafka 外置成功后，注册表才标记完成——
 * 下游系统（数据分析/风控引擎/通知服务）从 {@code battle.settled} topic 消费，
 * 以 battleId 为分区键保证同场战斗事件有序。
 */
@Externalized("battle.settled::battleId")
public record BattleSettledEvent(
        String battleId,
        long winnerId,
        long loserId,
        String endReason,
        String homeNode,
        boolean crossNode) {
}
