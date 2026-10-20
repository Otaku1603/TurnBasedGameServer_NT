package com.game.nt.gateway.internal.handlers;

import com.game.nt.gateway.internal.BattleCommandRouter;
import com.game.nt.gateway.internal.GameMessageHandler;
import com.game.nt.proto.GameProto.BattleRejoinResponse;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.MessageType;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/** 断线重连：返回当前盘面快照（真正生效，替代原项目的死代码）。 */
@Component
public class BattleRejoinHandler implements GameMessageHandler {

    private final BattleCommandRouter battleRouter;

    public BattleRejoinHandler(BattleCommandRouter battleRouter) {
        this.battleRouter = battleRouter;
    }

    @Override
    public MessageType type() {
        return MessageType.BATTLE_REJOIN;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GameMessage msg, Long userId) {
        // 路由器负责：本地盘面直返；跨节点则转发并由归属节点经推送通道回盘面（返回 null 不回包）
        GameMessage response = battleRouter.rejoin(userId, msg.getRequestId());
        if (response != null) {
            ctx.writeAndFlush(response);
        }
    }
}
