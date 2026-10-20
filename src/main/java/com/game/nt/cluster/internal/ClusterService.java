package com.game.nt.cluster.internal;

import com.game.nt.cluster.ClusterApi;
import com.game.nt.cluster.ClusterCommand;
import com.game.nt.cluster.ClusterCommandHandler;
import com.game.nt.cluster.ClusterLocalDelivery;
import com.game.nt.cluster.ClusterLuaReloadHandler;
import com.game.nt.proto.GameProto.GameMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 集群服务实现：Pub/Sub 收发 + 路由决策。
 *
 * <p>通道规划：
 * <ul>
 *   <li>{@code nt:cluster:push:{nodeId}} —— 发往某节点某用户的推送</li>
 *   <li>{@code nt:cluster:cmd:{nodeId}} —— 发往某节点的战斗指令</li>
 *   <li>{@code nt:cluster:broadcast} —— 全节点广播（from 自环跳过）</li>
 * </ul>
 */
@Service
public class ClusterService implements ClusterApi {

    private static final Logger log = LoggerFactory.getLogger(ClusterService.class);

    private static final String CHANNEL_PUSH = "nt:cluster:push:";
    private static final String CHANNEL_CMD = "nt:cluster:cmd:";
    private static final String CHANNEL_BROADCAST = "nt:cluster:broadcast";

    private final StringRedisTemplate redis;
    private final ClusterProperties props;
    private final RouteTable routeTable;
    private final NodeRegistry nodeRegistry;
    private final ObjectMapper objectMapper;
    private final ClusterLocalDelivery localDelivery;

    /** 集群控制面指标（C6：无指标时跨节点故障不可归因）。 */
    private final io.micrometer.core.instrument.Counter publishOk;
    private final io.micrometer.core.instrument.Counter publishFailed;
    private final io.micrometer.core.instrument.Counter commandsDispatched;
    private final io.micrometer.core.instrument.Counter kicks;
    private final io.micrometer.core.instrument.Counter luaReloads;

    /**
     * 惰性解析：ClusterService 若在构造期急切收集 handler，会形成
     * RoutingPushService → ClusterService → BattleCommandRouter → BattleManager → RoutingPushService
     * 的构造器环；ObjectProvider 延迟到真正收到指令时才解析，环即消失。
     */
    private final org.springframework.beans.factory.ObjectProvider<ClusterCommandHandler> handlers;

    /** 同 handlers：Lua 热更处理器（admin 实现）惰性解析，避免 cluster 反向依赖 admin。 */
    private final org.springframework.beans.factory.ObjectProvider<ClusterLuaReloadHandler> luaHandlers;

    public ClusterService(StringRedisTemplate redis,
                          ClusterProperties props,
                          RouteTable routeTable,
                          NodeRegistry nodeRegistry,
                          ObjectMapper objectMapper,
                          ClusterLocalDelivery localDelivery,
                          io.micrometer.core.instrument.MeterRegistry meterRegistry,
                          org.springframework.beans.factory.ObjectProvider<ClusterCommandHandler> handlers,
                          org.springframework.beans.factory.ObjectProvider<ClusterLuaReloadHandler> luaHandlers) {
        this.redis = redis;
        this.props = props;
        this.routeTable = routeTable;
        this.nodeRegistry = nodeRegistry;
        this.objectMapper = objectMapper;
        this.localDelivery = localDelivery;
        this.handlers = handlers;
        this.luaHandlers = luaHandlers;

        this.publishOk = io.micrometer.core.instrument.Counter.builder("nt_cluster_publish_total")
                .description("集群 Pub/Sub 发布成功数")
                .register(meterRegistry);
        this.publishFailed = io.micrometer.core.instrument.Counter.builder("nt_cluster_publish_failed_total")
                .description("集群 Pub/Sub 发布失败数（Redis 异常）")
                .register(meterRegistry);
        this.commandsDispatched = io.micrometer.core.instrument.Counter.builder("nt_cluster_command_dispatched_total")
                .description("本节点处理的跨节点战斗指令数")
                .register(meterRegistry);
        this.kicks = io.micrometer.core.instrument.Counter.builder("nt_cluster_kick_total")
                .description("本节点执行的跨节点顶号踢连接数")
                .register(meterRegistry);
        this.luaReloads = io.micrometer.core.instrument.Counter.builder("nt_cluster_lua_reload_total")
                .description("本节点应用的集群 Lua 热更次数")
                .register(meterRegistry);
    }

    private void dispatch(ClusterCommand command) {
        commandsDispatched.increment();
        for (ClusterCommandHandler handler : handlers.orderedStream().toList()) {
            handler.onCommand(command);
        }
    }

    private void dispatchLuaReload(String target, String script) {
        for (ClusterLuaReloadHandler handler : luaHandlers.orderedStream().toList()) {
            handler.onLuaReload(target, script);
        }
    }

    // ---------- 订阅（由 ClusterRedisConfig 注册容器后回调） ----------

    void subscribe(RedisMessageListenerContainer container) {
        container.addMessageListener((message, pattern) -> onMessage(new String(message.getBody())),
                new ChannelTopic(CHANNEL_PUSH + props.nodeId()));
        container.addMessageListener((message, pattern) -> onMessage(new String(message.getBody())),
                new ChannelTopic(CHANNEL_CMD + props.nodeId()));
        container.addMessageListener((message, pattern) -> onMessage(new String(message.getBody())),
                new ChannelTopic(CHANNEL_BROADCAST));
    }

    private void onMessage(String body) {
        try {
            ClusterEnvelope envelope = objectMapper.readValue(body, ClusterEnvelope.class);
            switch (envelope.kind()) {
                case ClusterEnvelope.KIND_PUSH ->
                        localDelivery.deliverLocal(envelope.userId(), envelope.parseMessage());
                case ClusterEnvelope.KIND_BROADCAST -> {
                    // 跳过自环：本节点广播时已本地投递过
                    if (!props.nodeId().equals(envelope.from())) {
                        localDelivery.deliverLocalBroadcast(envelope.parseMessage());
                    }
                }
                case ClusterEnvelope.KIND_CMD -> dispatch(envelope.command());
                case ClusterEnvelope.KIND_KICK -> {
                    // 顶号：来自其它节点的定向通知，关闭本节点上该用户的会话
                    if (!props.nodeId().equals(envelope.from())) {
                        if (localDelivery.kickLocal(envelope.userId())) {
                            kicks.increment();
                        }
                    }
                }
                case ClusterEnvelope.KIND_LUA_RELOAD -> {
                    // 发起节点已本地 apply，其余节点在此应用，避免重复
                    if (!props.nodeId().equals(envelope.from())) {
                        log.info("收到集群 Lua 热更广播: target={}, from={}", envelope.luaTarget(), envelope.from());
                        dispatchLuaReload(envelope.luaTarget(), envelope.luaScript());
                        luaReloads.increment();
                    }
                }
                default -> log.warn("未知集群消息类型: {}", envelope.kind());
            }
        } catch (Exception ex) {
            log.error("集群消息处理失败", ex);
        }
    }

    // ---------- ClusterApi ----------

    @Override
    public String selfNodeId() {
        return props.nodeId();
    }

    @Override
    public boolean forwardToUser(long userId, GameMessage message) {
        String node = routeTable.nodeOfUser(userId);
        if (node == null || node.equals(props.nodeId())) {
            return false;
        }
        return publish(CHANNEL_PUSH + node, ClusterEnvelope.push(props.nodeId(), userId, message));
    }

    @Override
    public void forwardCommand(ClusterCommand command) {
        String node = routeTable.nodeOfBattle(command.battleId());
        if (node == null) {
            log.warn("战斗归属节点未知，丢弃指令: battleId={}, type={}", command.battleId(), command.type());
            return;
        }
        if (node.equals(props.nodeId())) {
            // 归属就是本节点，直接本地执行
            dispatch(command);
            return;
        }
        publish(CHANNEL_CMD + node, ClusterEnvelope.cmd(props.nodeId(), command));
    }

    @Override
    public void publishBroadcast(GameMessage message) {
        publish(CHANNEL_BROADCAST, ClusterEnvelope.broadcast(props.nodeId(), message));
    }

    @Override
    public void bindUserRoute(long userId) {
        routeTable.bindUser(userId);
    }

    @Override
    public void unbindUserRoute(long userId) {
        routeTable.unbindUser(userId);
    }

    @Override
    public void kickUserOnNode(String nodeId, long userId) {
        if (nodeId == null || nodeId.equals(props.nodeId())) {
            return;
        }
        publish(CHANNEL_PUSH + nodeId, ClusterEnvelope.kick(props.nodeId(), userId));
    }

    @Override
    public void broadcastLuaReload(String target, String script) {
        publish(CHANNEL_BROADCAST, ClusterEnvelope.luaReload(props.nodeId(), target, script));
    }

    @Override
    public String nodeOfUser(long userId) {
        return routeTable.nodeOfUser(userId);
    }

    @Override
    public void bindBattleHome(String battleId) {
        routeTable.bindBattle(battleId);
    }

    @Override
    public String nodeOfBattle(String battleId) {
        return routeTable.nodeOfBattle(battleId);
    }

    @Override
    public void bindUserBattle(long userId, String battleId) {
        routeTable.bindUserBattle(userId, battleId);
    }

    @Override
    public void unbindUserBattle(long userId, String battleId) {
        routeTable.unbindUserBattle(userId, battleId);
    }

    @Override
    public String battleOfUser(long userId) {
        return routeTable.battleOfUser(userId);
    }

    @Override
    public List<NodeView> liveNodes() {
        var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault());
        return nodeRegistry.liveNodes().stream()
                .map(view -> new NodeView(view.nodeId(), view.startedAtEpochMs(),
                        formatter.format(java.time.Instant.ofEpochMilli(view.startedAtEpochMs()))))
                .collect(Collectors.toList());
    }    @Override
    public List<Long> routedOnlineUserIds() {
        return routeTable.routedUserIds();
    }

    private boolean publish(String channel, ClusterEnvelope envelope) {
        try {
            redis.convertAndSend(channel, objectMapper.writeValueAsString(envelope));
            publishOk.increment();
            return true;
        } catch (Exception ex) {
            publishFailed.increment();
            log.warn("集群消息发布失败: channel={}, {}", channel, ex.getMessage());
            return false;
        }
    }
}
