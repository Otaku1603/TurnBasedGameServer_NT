package com.game.nt.gateway.internal;

import com.game.nt.cluster.ClusterApi;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.MessageType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * 回归：网关读路径版本协商 —— 显式声明但低于 MIN_SUPPORTED 的客户端被拒绝；
 * version=0（未设置，存量工具帧）按当前版本语义放行。
 */
class GameChannelHandlerVersionTest {

    private EmbeddedChannel newHandlerChannel() {
        // 管线顺序对齐 NettyServer：encoder 在 head 侧、handler 在 tail 侧，
        // handler 的 ctx.writeAndFlush 向 head 传播时必经 encoder 编码为 WS 帧
        return new EmbeddedChannel(new GameMessageEncoder(), newHandler(false));
    }

    @Test
    void rejectsExplicitVersionBelowMinSupported() throws Exception {
        EmbeddedChannel channel = newHandlerChannel();
        try {
            GameMessage low = GameMessage.newBuilder()
                    .setVersion(1)
                    .setType(MessageType.HEARTBEAT)
                    .build();
            channel.writeInbound(new BinaryWebSocketFrame(
                    io.netty.buffer.Unpooled.wrappedBuffer(low.toByteArray())));

            BinaryWebSocketFrame out = channel.readOutbound();
            GameMessage resp = GameMessage.parseFrom(out.content().nioBuffer());
            assertEquals(MessageType.ERROR, resp.getType(), "低版本客户端应收到 ERROR");
            assertEquals(com.game.nt.proto.GameProto.ErrorCode.INVALID_ARGUMENT,
                    resp.getErrorResponse().getCode());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void allowsUnsetVersionForLegacyTooling() throws Exception {
        // version=0（未设置）不触发版本拒绝；路由到 no-op handler 后不应产生任何 ERROR 响应
        EmbeddedChannel channel = new EmbeddedChannel(new GameMessageEncoder(), newHandler(true));
        try {
            GameMessage unset = GameMessage.newBuilder()
                    .setType(MessageType.HEARTBEAT)
                    .build();
            channel.writeInbound(new BinaryWebSocketFrame(
                    io.netty.buffer.Unpooled.wrappedBuffer(unset.toByteArray())));

            BinaryWebSocketFrame out = channel.readOutbound();
            assertEquals(null, out, "放行路径不应产生 ERROR 响应");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private GameChannelHandler newHandler(boolean stubRouter) {
        MessageRouter router = mock(MessageRouter.class);
        if (stubRouter) {
            org.mockito.Mockito.when(router.resolve(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new GameMessageHandler() {
                        @Override public MessageType type() { return MessageType.HEARTBEAT; }
                        @Override public void handle(io.netty.channel.ChannelHandlerContext ctx,
                                                     GameMessage msg, Long userId) {
                            // no-op：本用例只验证版本校验放行，不关心业务响应
                        }
                    });
        }
        return new GameChannelHandler(
                router,
                new SessionManager(new SimpleMeterRegistry()),
                mock(BattleCommandRouter.class),
                mock(ClusterApi.class),
                new SimpleMeterRegistry());
    }
}
