package com.game.nt.gateway.internal.handlers;

import com.game.nt.gateway.internal.GameMessageHandler;
import com.game.nt.match.MatchApi;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.MessageType;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/** 取消匹配。 */
@Component
public class MatchCancelHandler implements GameMessageHandler {

    private final MatchApi matchApi;

    public MatchCancelHandler(MatchApi matchApi) {
        this.matchApi = matchApi;
    }

    @Override
    public MessageType type() {
        return MessageType.MATCH_CANCEL;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GameMessage msg, Long userId) {
        matchApi.cancel(userId);
    }
}
