package com.game.nt.gateway.internal;

import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.MessageType;
import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import io.netty.channel.ChannelHandler;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 游戏长连接业务处理器。
 *
 * <p>安全要点：<b>除 LOGIN 外，所有消息都要求会话已认证</b>；业务处理器拿到的 userId
 * 来自 Channel 属性而非消息体（{@link #channelRead0} 里统一注入）。
 */
@Component
@ChannelHandler.Sharable
public class GameChannelHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(GameChannelHandler.class);

    private final MessageRouter router;
    private final SessionManager sessionManager;
    private final BattleCommandRouter battleRouter;
    private final com.game.nt.cluster.ClusterApi clusterApi;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final io.micrometer.core.instrument.Counter messagesReceived;

    public GameChannelHandler(MessageRouter router, SessionManager sessionManager, BattleCommandRouter battleRouter,
                              com.game.nt.cluster.ClusterApi clusterApi,
                              io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.router = router;
        this.sessionManager = sessionManager;
        this.battleRouter = battleRouter;
        this.clusterApi = clusterApi;
        this.meterRegistry = meterRegistry;
        this.messagesReceived = io.micrometer.core.instrument.Counter
                .builder("nt.gateway.messages.received")
                .description("WS 消息接收总数")
                .register(meterRegistry);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) {
        GameMessage msg;
        try {
            msg = GameMessage.parseFrom(frame.content().nioBuffer());
        } catch (Exception ex) {
            log.warn("WS 帧解码失败，忽略: {}", ex.getMessage());
            return;
        }
        messagesReceived.increment();
        // traceId 由客户端上报，需净化后再进日志/MDC（防日志注入与超长污染）
        String traceId = sanitizeTraceId(msg.getTraceId());
        MDC.put("traceId", traceId);
        ctx.channel().attr(ChannelAttributes.TRACE_ID).set(traceId);

        long requestId = msg.getRequestId();
        try {
            MessageType type = msg.getType();
            if (type == MessageType.MESSAGE_TYPE_UNSPECIFIED) {
                sendError(ctx, requestId, ErrorCode.INVALID_ARGUMENT, "未指定消息类型");
                return;
            }

            GameMessageHandler handler = router.resolve(type);
            if (handler == null) {
                log.warn("未注册的消息类型: {}", type);
                sendError(ctx, requestId, ErrorCode.INVALID_ARGUMENT, "不支持的消息类型: " + type);
                return;
            }

            Long userId = sessionManager.userIdOf(ctx.channel());

            // 心跳允许未登录处理（保活语义，响应不含用户信息）
            if (type == MessageType.HEARTBEAT) {
                handler.handle(ctx, msg, userId);
                return;
            }

            // 除登录外，一律要求已认证会话
            if (type != MessageType.LOGIN && userId == null) {
                sendError(ctx, requestId, ErrorCode.UNAUTHORIZED, "尚未登录");
                return;
            }

            handler.handle(ctx, msg, userId);

        } catch (BusinessException ex) {
            sendError(ctx, requestId, ex.code(), ex.getMessage());
        } catch (Exception ex) {
            log.error("处理消息异常: type={}, requestId={}", msg.getType(), requestId, ex);
            sendError(ctx, requestId, ErrorCode.INTERNAL, null);
        } finally {
            MDC.remove("traceId");
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Long userId = sessionManager.userIdOf(ctx.channel());
        sessionManager.unbind(ctx.channel());
        if (userId != null) {
            clusterApi.unbindUserRoute(userId);
            // 在战斗中掉线：只做标记，给一个重连窗口；窗口内未归由定时任务判负
            // （战斗可能在集群其他节点上，路由器按 user-battle 表决定本地标记还是转发）
            if (battleRouter.hasBattle(userId)) {
                battleRouter.markDisconnected(userId);
                log.warn("战斗中断线，等待重连: userId={}", userId);
            } else {
                log.info("连接断开: userId={}, remote={}", userId, ctx.channel().remoteAddress());
            }
        } else {
            log.info("连接断开（未认证）: remote={}", ctx.channel().remoteAddress());
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent event && event.state() == IdleState.READER_IDLE) {
            log.warn("心跳超时，关闭连接: userId={}, remote={}",
                    sessionManager.userIdOf(ctx.channel()), ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("连接异常: remote={}, cause={}", ctx.channel().remoteAddress(), cause.toString());
        ctx.close();
    }

    private void sendError(ChannelHandlerContext ctx, long requestId, ErrorCode code, String message) {
        if (ctx.channel().isActive() && ctx.channel().isWritable()) {
            ctx.writeAndFlush(GatewayMessages.error(requestId, code, message));
        }
    }

    /** 只保留 [A-Za-z0-9_-]，最长 64；不可用时回退为新生成 ID。 */
    private static String sanitizeTraceId(String raw) {
        if (raw == null || raw.isBlank()) {
            return com.game.nt.shared.observability.TraceIdFilter.newTraceId();
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), 64));
        for (int i = 0; i < raw.length() && sb.length() < 64; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_') {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? com.game.nt.shared.observability.TraceIdFilter.newTraceId() : sb.toString();
    }
}
