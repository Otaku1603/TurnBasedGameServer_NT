package com.game.nt.gateway.internal.handlers;

import com.game.nt.gateway.ProtocolVersion;
import com.game.nt.gateway.internal.GameMessageHandler;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.HeartbeatResponse;
import com.game.nt.proto.GameProto.MessageType;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * 心跳：回填客户端发送时刻，客户端据此计算 RTT。
 * 心跳是唯一允许在未登录状态下处理（除登录外）的消息，用于连接保活。
 */
@Component
public class HeartbeatHandler implements GameMessageHandler {

    @Override
    public MessageType type() {
        return MessageType.HEARTBEAT;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GameMessage msg, Long userId) {
        long clientTime = msg.hasHeartbeat() ? msg.getHeartbeat().getClientTime() : 0L;
        HeartbeatResponse response = HeartbeatResponse.newBuilder()
                .setClientTime(clientTime)
                .setServerTime(System.currentTimeMillis())
                .build();
        ctx.writeAndFlush(GameMessage.newBuilder()
                .setVersion(ProtocolVersion.CURRENT)
                .setType(MessageType.HEARTBEAT)
                .setRequestId(msg.getRequestId())
                .setHeartbeatResponse(response)
                .build());
    }
}
