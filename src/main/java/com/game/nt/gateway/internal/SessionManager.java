package com.game.nt.gateway.internal;

import com.game.nt.cluster.ClusterLocalDelivery;
import com.game.nt.proto.GameProto.GameMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器：userId ⇄ Channel 双向映射；写前检查可写性做背压；同账号顶号。
 */
@Component
public class SessionManager implements ClusterLocalDelivery {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final Map<Long, Channel> byUser = new ConcurrentHashMap<>();
    private final Map<Channel, Long> byChannel = new ConcurrentHashMap<>();

    private final Counter droppedCounter;

    public SessionManager(MeterRegistry registry) {
        Gauge.builder("nt_gateway_online", byUser, Map::size)
                .description("当前在线会话数")
                .register(registry);
        this.droppedCounter = Counter.builder("nt_gateway_dropped_total")
                .description("因背压或离线而丢弃的推送数")
                .register(registry);
    }

    /** 绑定会话（登录成功后调用）。同账号旧连接会被踢下线。 */
    public void bind(long userId, Channel channel) {
        // 同一连接先登录 A 再登录 B 时，先把旧身份的双向映射清理干净，
        // 否则 byUser[A] 仍指向该连接，A 的推送会串到已认证为 B 的连接上。
        Long previousUserId = byChannel.put(channel, userId);
        if (previousUserId != null && previousUserId != userId) {
            byUser.remove(previousUserId, channel);
            log.warn("同连接切换身份: oldUserId={}, newUserId={}", previousUserId, userId);
        }
        Channel old = byUser.put(userId, channel);
        if (old != null && old != channel) {
            byChannel.remove(old);
            if (old.isActive()) {
                log.warn("检测到顶号，踢下线: userId={}, old={}", userId, old.remoteAddress());
                old.close();
            }
        }
        channel.attr(ChannelAttributes.USER_ID).set(userId);
        log.info("会话绑定: userId={}, online={}", userId, byUser.size());
    }

    /**
     * 解绑（连接断开时调用）。使用 remove(key,value) 防止误删同账号的新连接。
     *
     * @return 该连接在断开前是否仍是该用户的当前会话：
     *         {@code false} 表示会话已被新连接接管（同节点顶号/切号），
     *         调用方不得再清理集群路由或标记战斗断线（防误删新会话路由）。
     */
    public boolean unbind(Channel channel) {
        Long userId = byChannel.remove(channel);
        if (userId != null) {
            boolean current = byUser.remove(userId, channel);
            log.info("会话解绑: userId={}, 仍是当前会话={}, online={}", userId, current, byUser.size());
            return current;
        }
        return false;
    }

    public Long userIdOf(Channel channel) {
        return channel.attr(ChannelAttributes.USER_ID).get();
    }

    /**
     * 跨节点顶号：关闭本节点上该用户的会话。
     *
     * <p>刻意保留 {@link ChannelAttributes#USER_ID}，让 {@code channelInactive} 照常触发
     * 解绑与集群路由清理（COMPARE_AND_DEL 保证不会误删新节点写入的路由）。
     */
    @Override
    public boolean kickLocal(long userId) {
        Channel channel = byUser.get(userId);
        if (channel == null) {
            return false;
        }
        byUser.remove(userId, channel);
        byChannel.remove(channel);
        if (channel.isActive()) {
            log.warn("收到跨节点顶号通知，踢下线: userId={}, remote={}", userId, channel.remoteAddress());
            channel.close();
        }
        return true;
    }

    /** 只投本节点会话（集群 Pub/Sub 收到本节点消息后走这里，不产生二次转发）。 */
    @Override
    public boolean deliverLocal(long userId, GameMessage message) {
        Channel channel = byUser.get(userId);
        if (channel == null || !channel.isActive()) {
            return false;
        }
        if (!channel.isWritable()) {
            // 背压：对端消费不过来，宁可丢弃本次推送也不堆积内存
            droppedCounter.increment();
            log.warn("连接不可写，丢弃推送: userId={}, type={}", userId, message.getType());
            return false;
        }
        channel.writeAndFlush(message);
        return true;
    }

    @Override
    public int deliverLocalBroadcast(GameMessage message) {
        return broadcast(message);
    }

    public boolean isOnline(long userId) {
        Channel channel = byUser.get(userId);
        return channel != null && channel.isActive();
    }

    public int onlineCount() {
        return byUser.size();
    }

    /** 仅本节点广播（集群广播由 RoutingPushService 叠加）。 */
    public int broadcast(GameMessage message) {
        int delivered = 0;
        for (Long userId : byUser.keySet()) {
            if (deliverLocal(userId, message)) {
                delivered++;
            }
        }
        return delivered;
    }

    /** 本节点在线用户快照（只读副本，避免外部遍历活视图）。 */
    public Set<Long> onlineUserIds() {
        return Set.copyOf(byUser.keySet());
    }
}
