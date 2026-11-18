package com.game.nt.gateway.internal;

import com.game.nt.cluster.ClusterApi;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.shared.push.ClientPushApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 推送门面：本地直发，未命中按 Redis 路由转发；广播由各节点自投。 */
@Component
public class RoutingPushService implements ClientPushApi {

    private static final Logger log = LoggerFactory.getLogger(RoutingPushService.class);

    private final SessionManager sessionManager;
    private final ClusterApi clusterApi;

    /** 推送路由指标：本地命中 / 跨节点转发 / 不可达 / 广播。 */
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
        try {
            if (clusterApi.forwardToUser(userId, message)) {
                forwarded.increment();
                return true;
            }
        } catch (Exception ex) {
            // 推送是尽力而为：Redis 故障时不能把异常抛回战斗房间/业务线程；
            // 关键路径（匹配/战斗指令/结算）由各自模块按 fail-closed 处理。
            log.warn("跨节点推送失败（按不可达处理）: userId={}, type={}, {}", userId,
                    message.getType(), ex.getMessage());
        }
        unreachable.increment();
        return false;
    }

    @Override
    public boolean isOnline(long userId) {
        if (sessionManager.isOnline(userId)) {
            return true;
        }
        try {
            return clusterApi.nodeOfUser(userId) != null;
        } catch (Exception ex) {
            log.warn("查询跨节点在线状态失败: userId={}, {}", userId, ex.getMessage());
            return false;
        }
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
