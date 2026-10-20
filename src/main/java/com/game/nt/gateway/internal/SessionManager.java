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
 * 会话管理器：维护 userId ⇄ Channel 的双向映射，并提供带背压保护的定向推送。
 *
 * <p>相对原项目的关键改进：
 * <ul>
 *   <li><b>背压</b>：写之前判断 {@link Channel#isWritable()}，不可写则丢弃并计数，
 *       避免慢客户端把出站队列堆积到 OOM（原项目直接 writeAndFlush）。</li>
 *   <li><b>可观测</b>：在线人数、丢弃消息数暴露为 Micrometer 指标。</li>
 *   <li><b>顶号</b>：同账号二次登录时踢掉旧连接；跨节点场景由集群 KICK 通知驱动
 *       {@link #kickLocal(long)}（见 {@code ClusterLocalDelivery}）。</li>
 *   <li><b>集群分工</b>（A9/跨服）：本类只负责<b>本地会话</b>投递（实现 {@link ClusterLocalDelivery}，
 *       供集群模块回调，不产生二次转发）；跨节点路由转发由 {@code RoutingPushService} 承担，
 *       路由表读写由 handler 在登录/断开时触发 —— 避免构造器循环依赖。</li>
 * </ul>
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
        Channel old = byUser.put(userId, channel);
        if (old != null && old != channel) {
            byChannel.remove(old);
            if (old.isActive()) {
                log.warn("检测到顶号，踢下线: userId={}, old={}", userId, old.remoteAddress());
                old.close();
            }
        }
        byChannel.put(channel, userId);
        channel.attr(ChannelAttributes.USER_ID).set(userId);
        log.info("会话绑定: userId={}, online={}", userId, byUser.size());
    }

    /** 解绑（连接断开时调用）。使用 remove(key,value) 防止误删同账号的新连接。 */
    public void unbind(Channel channel) {
        Long userId = byChannel.remove(channel);
        if (userId != null) {
            byUser.remove(userId, channel);
            log.info("会话解绑: userId={}, online={}", userId, byUser.size());
        }
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
