package com.game.nt.gateway.internal.handlers;

import com.game.nt.gateway.internal.BattleCommandRouter;
import com.game.nt.gateway.internal.GameMessageHandler;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.MessageType;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/** 投降。 */
@Component
public class BattleSurrenderHandler implements GameMessageHandler {

    private final BattleCommandRouter battleRouter;

    public BattleSurrenderHandler(BattleCommandRouter battleRouter) {
        this.battleRouter = battleRouter;
    }

    @Override
    public MessageType type() {
        return MessageType.BATTLE_SURRENDER;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GameMessage msg, Long userId) {
        String battleId = msg.getBattleSurrenderRequest().getBattleId();
        battleRouter.surrender(userId, battleId);
    }
}
