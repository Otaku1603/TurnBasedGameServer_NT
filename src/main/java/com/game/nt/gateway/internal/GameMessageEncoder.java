package com.game.nt.gateway.internal;

import com.game.nt.gateway.ProtocolVersion;
import com.game.nt.proto.GameProto.GameMessage;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

import java.util.List;

/**
 * 出站编码器：GameMessage → WS 二进制帧。
 *
 * <p>服务端所有 WS 出站消息（响应 + 会话推送 + 集群转发落地）都复用同一 writeAndFlush 路径，
 * 因此这里是对外契约的唯一收口 —— 未显式设置版本的消息统一按当前协议版本兜底补齐，
 * 保证 BATTLE_START/UPDATE/END、MATCH_SUCCESS、MAIL_NOTIFY、CHAT_PUSH 等推送
 * 与协议 v2 的 version=2 契约一致，版本校验不会集中断裂。
 */
public class GameMessageEncoder extends MessageToMessageEncoder<GameMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, GameMessage msg, List<Object> out) {
        GameMessage outbound = msg;
        if (outbound.getVersion() == 0) {
            outbound = outbound.toBuilder()
                    .setVersion(ProtocolVersion.CURRENT).build();
        }
        out.add(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(outbound.toByteArray())));
    }
}
