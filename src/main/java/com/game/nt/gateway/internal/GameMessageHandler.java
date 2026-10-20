package com.game.nt.gateway.internal;

import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.MessageType;
import io.netty.channel.ChannelHandlerContext;

/**
 * 单类消息处理器（替代原项目的巨型 switch）。
 *
 * <p>每种 {@link MessageType} 对应一个 Spring Bean，由 {@link MessageRouter} 自动注册。
 * 新增消息类型只需新增一个实现类，无需改动分发逻辑（开闭原则）。
 */
public interface GameMessageHandler {

    /** 本处理器负责的消息类型。 */
    MessageType type();

    /**
     * 处理消息。
     *
     * @param ctx    连接上下文
     * @param msg    消息体
     * @param userId 已认证用户 ID；未登录为 null（仅 LOGIN 类消息允许）
     */
    void handle(ChannelHandlerContext ctx, GameMessage msg, Long userId) throws Exception;
}
