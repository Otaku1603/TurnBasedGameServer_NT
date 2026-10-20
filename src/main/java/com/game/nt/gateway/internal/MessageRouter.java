package com.game.nt.gateway.internal;

import com.game.nt.proto.GameProto.MessageType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 消息路由表：启动时把所有 {@link GameMessageHandler} 按 {@link MessageType} 收集起来。
 */
@Component
public class MessageRouter {

    private final Map<MessageType, GameMessageHandler> handlers = new EnumMap<>(MessageType.class);

    public MessageRouter(List<GameMessageHandler> handlerBeans) {
        for (GameMessageHandler handler : handlerBeans) {
            GameMessageHandler previous = handlers.put(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException("消息类型重复注册: " + handler.type()
                        + " -> " + previous.getClass() + " / " + handler.getClass());
            }
        }
    }

    /** 返回处理器；无对应处理器时返回 null。 */
    public GameMessageHandler resolve(MessageType type) {
        return handlers.get(type);
    }

    public int size() {
        return handlers.size();
    }
}
