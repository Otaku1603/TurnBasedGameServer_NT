package com.game.nt.gateway.internal;

import com.game.nt.gateway.ProtocolVersion;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.MessageType;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 回归：所有出站消息必经 GameMessageEncoder —— 遍历全部 MessageType 构造未设版本的
 * 消息，断言编码器统一兜底 version=2（与协议 v2 契约一致）；
 * 已显式设置版本的消息不得被覆盖。
 */
class GameMessageEncoderTest {

    @Test
    void fillsProtocolVersionForEveryMessageType() {
        for (MessageType type : MessageType.values()) {
            if (type == MessageType.MESSAGE_TYPE_UNSPECIFIED || type == MessageType.UNRECOGNIZED) {
                continue; // UNSPECIFIED 由入站校验拒绝；UNRECOGNIZED 不可用于 setType
            }
            EmbeddedChannel channel = new EmbeddedChannel(new GameMessageEncoder());
            try {
                GameMessage msg = GameMessage.newBuilder().setType(type).build();
                channel.writeOutbound(msg);
                BinaryWebSocketFrame frame = channel.readOutbound();
                GameMessage decoded = GameMessage.parseFrom(frame.content().nioBuffer());
                assertEquals(ProtocolVersion.CURRENT, decoded.getVersion(),
                        "出站消息 " + type + " 应被兜底设置为当前协议版本");
                assertEquals(type, decoded.getType());
            } catch (Exception ex) {
                throw new IllegalStateException("编码断言失败: " + type, ex);
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void keepsExplicitVersion() {
        EmbeddedChannel channel = new EmbeddedChannel(new GameMessageEncoder());
        try {
            GameMessage msg = GameMessage.newBuilder()
                    .setVersion(9)
                    .setType(MessageType.BATTLE_START)
                    .build();
            channel.writeOutbound(msg);
            BinaryWebSocketFrame frame = channel.readOutbound();
            GameMessage decoded = GameMessage.parseFrom(frame.content().nioBuffer());
            assertEquals(9, decoded.getVersion(), "已显式设置的版本不得被覆盖");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
