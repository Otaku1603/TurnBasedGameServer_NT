package com.game.nt.gateway.internal.handlers;

import com.game.nt.gateway.internal.GameMessageHandler;
import com.game.nt.match.MatchApi;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.MessageType;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/** 请求匹配。身份取自会话（消息体不再携带 userId）。 */
@Component
public class MatchHandler implements GameMessageHandler {

    private final MatchApi matchApi;

    public MatchHandler(MatchApi matchApi) {
        this.matchApi = matchApi;
    }

    @Override
    public MessageType type() {
        return MessageType.MATCH_REQUEST;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GameMessage msg, Long userId) {
        matchApi.join(userId);
    }
}
