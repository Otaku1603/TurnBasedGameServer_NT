package com.game.nt.gateway.internal;

import com.game.nt.cluster.ClusterApi;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.shared.push.ClientPushApi;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 集群感知的推送门面（ClientPushApi 的唯一对外实现）。
 *
 * <ul>
 *   <li>{@code sendToUser}：先本地直发（背压保护在 SessionManager），未命中按 Redis 路由表
 *       经 Pub/Sub 转发到用户归属节点。</li>
 *   <li>{@code broadcast}：本节点全部投递 + 集群广播（各节点自投，from 自环跳过防重复）。</li>
 *   <li>{@code isOnline}：本节点有会话，或路由表指向其他节点（用户连在别处）。</li>
 * </ul>
 *
 * <p>业务模块（battle/match/mail/social/activity...）全部经本门面推送，
 * 对"单机还是集群"完全无感 —— 这就是"可分布式"落地的接缝。
 */
@Component
public class RoutingPushService implements ClientPushApi {

    private final SessionManager sessionManager;
    private final ClusterApi clusterApi;

    /** 推送路由指标（C6）：本地命中 / 跨节点转发 / 不可达 / 广播。 */
    private final io.micrometer.core.instrument.Counter localHits;
    private final io.micrometer.core.instrument.Counter forwarded;
    private final io.micrometer.core.instrument.Counter unreachable;
    private final io.micrometer.core.instrument.Counter broadcasts;

    public RoutingPushService(SessionManager sessionManager, ClusterApi clusterApi,
                              io.micrometer.core.instrument.MeterRegistry registry) {
        this.sessionManager = sessionManager;
        this.clusterApi = clusterApi;
        this.localHits = io.micrometer.core.instrument.Counter.builder("nt_push_local_total")
                .description("本节点直发的推送数")
                .register(registry);
        this.forwarded = io.micrometer.core.instrument.Counter.builder("nt_push_forwarded_total")
                .description("跨节点转发的推送数")
                .register(registry);
        this.unreachable = io.micrometer.core.instrument.Counter.builder("nt_push_unreachable_total")
                .description("无路由/不可达而丢弃的推送数")
                .register(registry);
        this.broadcasts = io.micrometer.core.instrument.Counter.builder("nt_push_broadcast_total")
                .description("集群广播次数")
                .register(registry);
    }

    @Override
    public boolean sendToUser(long userId, GameMessage message) {
        if (sessionManager.deliverLocal(userId, message)) {
            localHits.increment();
            return true;
        }
        if (clusterApi.forwardToUser(userId, message)) {
            forwarded.increment();
            return true;
        }
        unreachable.increment();
        return false;
    }

    @Override
    public boolean isOnline(long userId) {
        return sessionManager.isOnline(userId) || clusterApi.nodeOfUser(userId) != null;
    }

    @Override
    public int onlineCount() {
        return sessionManager.onlineCount();
    }

    @Override
    public int broadcast(GameMessage message) {
        broadcasts.increment();
        int delivered = sessionManager.broadcast(message);
        clusterApi.publishBroadcast(message);
        return delivered;
    }

    @Override
    public Set<Long> onlineUserIds() {
        return sessionManager.onlineUserIds();
    }
}
