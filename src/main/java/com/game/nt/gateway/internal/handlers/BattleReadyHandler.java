package com.game.nt.gateway.internal.handlers;

import com.game.nt.gateway.internal.BattleCommandRouter;
import com.game.nt.gateway.internal.GameMessageHandler;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.MessageType;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/** 战斗准备。 */
@Component
public class BattleReadyHandler implements GameMessageHandler {

    private final BattleCommandRouter battleRouter;

    public BattleReadyHandler(BattleCommandRouter battleRouter) {
        this.battleRouter = battleRouter;
    }

    @Override
    public MessageType type() {
        return MessageType.BATTLE_READY;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GameMessage msg, Long userId) {
        String battleId = msg.getBattleReadyRequest().getBattleId();
        battleRouter.playerReady(userId, battleId);
    }
}
