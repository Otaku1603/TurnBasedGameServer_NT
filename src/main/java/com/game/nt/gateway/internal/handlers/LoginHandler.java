package com.game.nt.gateway.internal.handlers;

import com.game.nt.gateway.ProtocolVersion;
import com.game.nt.gateway.internal.ChannelAttributes;
import com.game.nt.gateway.internal.GameMessageHandler;
import com.game.nt.cluster.ClusterApi;
import com.game.nt.gateway.internal.SessionManager;
import com.game.nt.identity.IdentityApi;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.LoginResponse;
import com.game.nt.proto.GameProto.MessageType;
import com.game.nt.shared.security.JwtService;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 登录：校验 JWT → 绑定会话身份。
 * 登录成功后，后续业务消息的身份一律来自 Channel 属性，不再看消息体。
 */
@Component
public class LoginHandler implements GameMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(LoginHandler.class);

    private final JwtService jwtService;
    private final IdentityApi identityApi;
    private final SessionManager sessionManager;
    private final ClusterApi clusterApi;

    public LoginHandler(JwtService jwtService, IdentityApi identityApi, SessionManager sessionManager,
                        ClusterApi clusterApi) {
        this.jwtService = jwtService;
        this.identityApi = identityApi;
        this.sessionManager = sessionManager;
        this.clusterApi = clusterApi;
    }

    @Override
    public MessageType type() {
        return MessageType.LOGIN;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GameMessage msg, Long currentUserId) {
        Long userId = jwtService.verifyAndGetUserId(msg.getToken()).orElse(null);
        if (userId == null) {
            reply(ctx, msg.getRequestId(), LoginResponse.newBuilder()
                    .setSuccess(false).setMessage("令牌无效或已过期").build());
            return;
        }

        IdentityApi.AccountInfo account = identityApi.findByUserId(userId).orElse(null);
        if (account == null) {
            reply(ctx, msg.getRequestId(), LoginResponse.newBuilder()
                    .setSuccess(false).setMessage("账户不存在").build());
            return;
        }
        if (account.banned()) {
            log.warn("封禁账户尝试登录: userId={}", userId);
            reply(ctx, msg.getRequestId(), LoginResponse.newBuilder()
                    .setSuccess(false).setMessage("账户已被封禁").build());
            return;
        }

        // 关键：把身份写入 Channel，后续业务消息只认它
        // 跨节点顶号：bind 前先读旧路由，bindUserRoute 之后再通知旧节点踢连接
        // （顺序不可颠倒：否则旧节点断开时可能删掉尚未被覆盖的路由）
        String previousNode = clusterApi.nodeOfUser(userId);

        sessionManager.bind(userId, ctx.channel());
        ctx.channel().attr(ChannelAttributes.USERNAME).set(account.username());
        // 集群路由：声明"该用户在本节点"（跨节点推送/匹配/战斗转发依赖此表）
        clusterApi.bindUserRoute(userId);
        if (previousNode != null && !previousNode.equals(clusterApi.selfNodeId())) {
            clusterApi.kickUserOnNode(previousNode, userId);
            log.warn("跨节点顶号: userId={}, 旧节点={}", userId, previousNode);
        }

        reply(ctx, msg.getRequestId(), LoginResponse.newBuilder()
                .setSuccess(true).setMessage("登录成功").setUserId(userId).build());
        log.info("玩家登录成功: userId={}, username={}", userId, account.username());
    }

    private void reply(ChannelHandlerContext ctx, long requestId, LoginResponse response) {
        ctx.writeAndFlush(GameMessage.newBuilder()
                .setVersion(ProtocolVersion.CURRENT)
                .setType(MessageType.LOGIN_RESPONSE)
                .setRequestId(requestId)
                .setLoginResponse(response)
                .build());
    }
}
