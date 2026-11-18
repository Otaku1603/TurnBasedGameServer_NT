package com.game.nt.cluster;

/**
 * 跨节点战斗指令（经 Redis Pub/Sub 转发，归属节点上的 {@link ClusterCommandHandler} 执行）。
 *
 * <p>指令载荷刻意保持"原始客户端输入"级别（userId + battleId + 动作），
 * 归属节点执行时仍走本地 {@code BattleApi} 的完整校验——
 * 转发不绕过任何业务规则，只是换了入口。
 */
public record ClusterCommand(
        Type type,
        String battleId,
        long userId,
        String actionType,
        int paramId) {

    public enum Type {
        /** 战斗准备 */
        BATTLE_READY,
        /** 战斗操作 */
        BATTLE_ACTION,
        /** 投降 */
        BATTLE_SURRENDER,
        /** 断线重连（归属节点计算盘面后经推送通道下发） */
        BATTLE_REJOIN,
        /** 断线标记（用于超时判负） */
        MARK_DISCONNECTED,
        /** 清除断线标记 */
        CLEAR_DISCONNECTED
    }
}
