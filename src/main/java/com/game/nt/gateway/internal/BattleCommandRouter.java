package com.game.nt.gateway.internal;

import com.game.nt.battle.BattleApi;
import com.game.nt.cluster.ClusterApi;
import com.game.nt.cluster.ClusterCommand;
import com.game.nt.cluster.ClusterCommandHandler;
import com.game.nt.proto.GameProto.BattleRejoinResponse;
import com.game.nt.proto.GameProto.ActionType;
import com.game.nt.gateway.ProtocolVersion;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.MessageType;
import com.game.nt.shared.push.ClientPushApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 战斗指令路由器（跨服入口）。
 *
 * <p>出方向：handler 的战斗指令先看战斗归属节点——
 * 本节点直接调本地 {@link BattleApi}；非本节点则经集群转发到归属节点执行。
 *
 * <p>入方向：实现 {@link ClusterCommandHandler}，收到归属节点转来的指令后
 * 调本地 BattleApi（完整走本地校验，转发不绕过任何业务规则）。
 * 断线/重连类指令以"用户"为键路由（battleId 由 Redis 的 user-battle 表解析）。
 *
 * <p>战斗状态仍然只在归属节点存在——这是"路由指令，而非复制状态"的 Actor 分布式思路。
 */
@Component
public class BattleCommandRouter implements ClusterCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(BattleCommandRouter.class);

    private final BattleApi battleApi;
    private final ClusterApi clusterApi;
    private final ClientPushApi clientPushApi;

    public BattleCommandRouter(BattleApi battleApi, ClusterApi clusterApi, ClientPushApi clientPushApi) {
        this.battleApi = battleApi;
        this.clusterApi = clusterApi;
        this.clientPushApi = clientPushApi;
    }

    // ==================== 出方向（handler 调用） ====================

    public void playerReady(long userId, String battleId) {
        route(battleId, userId, new ClusterCommand(ClusterCommand.Type.BATTLE_READY, battleId, userId, null, 0),
                () -> battleApi.playerReady(userId, battleId));
    }

    public void action(long userId, String battleId, ActionType actionType, int paramId) {
        route(battleId, userId, new ClusterCommand(ClusterCommand.Type.BATTLE_ACTION, battleId, userId, actionType.name(), paramId),
                () -> battleApi.handleAction(userId, battleId, actionType, paramId));
    }

    public void surrender(long userId, String battleId) {
        route(battleId, userId, new ClusterCommand(ClusterCommand.Type.BATTLE_SURRENDER, battleId, userId, null, 0),
                () -> battleApi.surrender(userId, battleId));
    }

    /**
     * 重连：本地有战斗则返回盘面；否则转发到归属节点（盘面由归属节点推送回来）。
     *
     * <p>跨节点时**不**立即回 success=true——空盘面会被客户端误当作真实盘面
     * （ReconnectRecovery 用它覆盖 CurrentBattle → NPE）。归属节点的响应经
     * 推送通道异步下发，此处不占位回包；fire-and-forget 丢失时客户端按超时重连兜底。
     */
    public GameMessage rejoin(long userId, long requestId) {
        String battleId = clusterApi.battleOfUser(userId);
        String home = battleId == null ? null : clusterApi.nodeOfBattle(battleId);
        if (battleId == null || home == null || home.equals(clusterApi.selfNodeId())) {
            // 本地处理：清除断线标记 + 返回盘面
            battleApi.clearDisconnected(userId);
            GameMessage snapshot = battleApi.rejoin(userId);
            if (snapshot != null) {
                return snapshot;
            }
            return GameMessage.newBuilder()
                    .setVersion(ProtocolVersion.CURRENT)
                    .setType(MessageType.BATTLE_REJOIN_RESPONSE)
                    .setRequestId(requestId)
                    .setBattleRejoinResponse(BattleRejoinResponse.newBuilder()
                            .setSuccess(false)
                            .setMessage("没有进行中的战斗")
                            .build())
                    .build();
        }
        // 跨节点：盘面在归属节点上，转发后由归属节点经推送通道回盘面（异步）
        log.info("战斗重连跨节点转发: battleId={}, userId={}, -> {}", battleId, userId, home);
        battleApi.clearDisconnected(userId);
        clusterApi.forwardCommand(new ClusterCommand(
                ClusterCommand.Type.BATTLE_REJOIN, battleId, userId, null, 0));
        return null;
    }

    /** 断线标记（channelInactive）：战斗可能在别的节点。 */
    public void markDisconnected(long userId) {
        routeByUser(userId, ClusterCommand.Type.MARK_DISCONNECTED,
                () -> battleApi.markDisconnected(userId));
    }

    /** 清除断线标记（重连成功）。 */
    public void clearDisconnected(long userId) {
        routeByUser(userId, ClusterCommand.Type.CLEAR_DISCONNECTED,
                () -> battleApi.clearDisconnected(userId));
    }

    /** 用户是否有进行中的战斗（跨节点感知：查 Redis user-battle 表）。 */
    public boolean hasBattle(long userId) {
        return clusterApi.battleOfUser(userId) != null;
    }

    // ==================== 路由决策 ====================

    private void route(String battleId, long userId, ClusterCommand command, Runnable localCall) {
        String home = clusterApi.nodeOfBattle(battleId);
        if (home == null || home.equals(clusterApi.selfNodeId())) {
            localCall.run();
            return;
        }
        log.info("战斗指令跨节点转发: battleId={}, userId={}, -> {}", battleId, userId, home);
        clusterApi.forwardCommand(command);
    }

    private void routeByUser(long userId, ClusterCommand.Type type, Runnable localCall) {
        String battleId = clusterApi.battleOfUser(userId);
        if (battleId == null) {
            localCall.run();
            return;
        }
        route(battleId, userId, new ClusterCommand(type, battleId, userId, null, 0), localCall);
    }

    // ==================== 入方向（归属节点执行） ====================

    @Override
    public void onCommand(ClusterCommand command) {
        try {
            doCommand(command);
        } catch (com.game.nt.shared.error.BusinessException ex) {
            // 正常场景：指令转发在途时战斗恰好结束（at-least-once 语义下的良性竞态）
            log.info("跨节点指令被拒绝（战斗可能已结束）: battleId={}, type={}, msg={}",
                    command.battleId(), command.type(), ex.getMessage());
        }
    }

    private void doCommand(ClusterCommand command) {
        switch (command.type()) {
            case BATTLE_READY -> battleApi.playerReady(command.userId(), command.battleId());
            case BATTLE_ACTION -> battleApi.handleAction(command.userId(), command.battleId(),
                    ActionType.valueOf(command.actionType()), command.paramId());
            case BATTLE_SURRENDER -> battleApi.surrender(command.userId(), command.battleId());
            case BATTLE_REJOIN -> {
                battleApi.clearDisconnected(command.userId());
                GameMessage snapshot = battleApi.rejoin(command.userId());
                if (snapshot != null) {
                    clientPushApi.sendToUser(command.userId(), snapshot);
                } else {
                    clientPushApi.sendToUser(command.userId(), GameMessage.newBuilder()
                            .setType(MessageType.BATTLE_REJOIN_RESPONSE)
                            .setBattleRejoinResponse(BattleRejoinResponse.newBuilder()
                                    .setSuccess(false)
                                    .setMessage("没有进行中的战斗")
                                    .build())
                            .build());
                }
            }
            case MARK_DISCONNECTED -> battleApi.markDisconnected(command.userId());
            case CLEAR_DISCONNECTED -> battleApi.clearDisconnected(command.userId());
        }
    }
}
