package com.game.nt.gateway.internal.handlers;

import com.game.nt.gateway.internal.BattleCommandRouter;
import com.game.nt.risk.RiskApi;
import com.game.nt.gateway.internal.GameMessageHandler;
import com.game.nt.proto.GameProto.ActionType;
import com.game.nt.proto.GameProto.BattleActionRequest;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.MessageType;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * 战斗操作。
 *
 * <p>安全要点：只接受"使用哪个技能/道具"的意图，伤害由服务端计算；
 * 战斗归属与回合权在战斗房间内二次校验（越权直接拒绝）。
 */
@Component
public class BattleActionHandler implements GameMessageHandler {

    private final BattleCommandRouter battleRouter;
    private final RiskApi riskApi;

    public BattleActionHandler(BattleCommandRouter battleRouter, RiskApi riskApi) {
        this.battleRouter = battleRouter;
        this.riskApi = riskApi;
    }

    @Override
    public MessageType type() {
        return MessageType.BATTLE_ACTION;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GameMessage msg, Long userId) {
        riskApi.onBattleAction(userId); // 限流 + 机器节奏检测（超限抛 RATE_LIMITED）
        BattleActionRequest request = msg.getBattleActionRequest();
        ActionType actionType = request.getActionType();
        battleRouter.action(userId, request.getBattleId(), actionType, request.getParamId());
    }
}
