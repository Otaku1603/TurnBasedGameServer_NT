package com.game.nt.cluster.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 节点注册表：{@code cluster:node:{id}} TTL + 心跳。
 * 启动时抢占唯一 NODE_ID 并清理本节点上一世残留路由；停机释放身份；Redis 异常按配置失败关闭。
 */
@Component
public class NodeRegistry implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NodeRegistry.class);
    private static final String NODE_PREFIX = "cluster:node:";
    private static final String BATTLE_PREFIX = "cluster:battle:";
    private static final String USER_BATTLE_PREFIX = "cluster:userbattle:";
    private static final String ROUTE_PREFIX = "cluster:route:";

    /** 仅当值仍等于期望值/前缀时才删除（防误删其他实例刚写入的值）。 */
    private static final DefaultRedisScript<Long> COMPARE_AND_DEL = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> COMPARE_PREFIX_AND_DEL = new DefaultRedisScript<>("""
            local v = redis.call('get', KEYS[1])
            if v and string.sub(v, 1, string.len(ARGV[1])) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> COMPARE_PREFIX_AND_SET = new DefaultRedisScript<>("""
            local v = redis.call('get', KEYS[1])
            if v and string.sub(v, 1, string.len(ARGV[1])) == ARGV[1] then
                redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3])
                return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ClusterProperties props;
    private final boolean allowDegrade;

    /** 本进程实例 ID，用于区分"同名节点的上一世"与本世。 */
    private final String instanceId = UUID.randomUUID().toString();
    /** 容器/主机身份：同一容器的重启应能接管上一进程的注册。 */
    private static final String HOST = System.getenv().getOrDefault("HOSTNAME", "unknown-host");
    private final long startedAt = System.currentTimeMillis();
    private volatile boolean claimed;

    public NodeRegistry(StringRedisTemplate redis, ClusterProperties props,
                        @Value("${app.redis.allow-degrade:false}") boolean allowDegrade) {
        this.redis = redis;
        this.props = props;
        this.allowDegrade = allowDegrade;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            claimNodeId();
            cleanupStaleState();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            if (!allowDegrade) {
                throw new IllegalStateException("节点注册失败（app.redis.allow-degrade=false，拒绝以降级模式启动）", ex);
            }
            log.warn("节点注册失败（降级模式继续）: {}", ex.getMessage());
        }
    }

    private void claimNodeId() {
        if (claimed) {
            return;
        }
        String key = NODE_PREFIX + props.nodeId();
        long now = System.currentTimeMillis();
        String myValue = value(now);
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                Boolean ok = redis.opsForValue().setIfAbsent(
                        key, myValue, Duration.ofSeconds(props.nodeTtlSeconds()));
                if (Boolean.TRUE.equals(ok)) {
                    claimed = true;
                    log.info("节点身份抢占成功: NODE_ID={}, instance={}, ttl={}s",
                            props.nodeId(), instanceId, props.nodeTtlSeconds());
                    return;
                }

                String existing = redis.opsForValue().get(key);
                if (existing == null) {
                    continue;
                }
                if (existing.startsWith(instanceId + "|")) {
                    // 心跳调度可能先于 ApplicationRunner 完成抢占；同一实例视为已注册
                    claimed = true;
                    return;
                }
                if (existing != null && HOST.equals(hostOf(existing))) {
                    // 同一容器的上一进程（重启场景）：直接接管，不等 TTL
                    log.warn("接管同主机上一进程的节点注册: NODE_ID={}, host={}", props.nodeId(), HOST);
                    redis.execute(COMPARE_AND_DEL, List.of(key), existing);
                    continue;
                }
                long heartbeatAt = heartbeatAt(existing);
                long staleAfter = Math.max(props.heartbeatIntervalMs() * 2, 15_000L);
                if (heartbeatAt > 0 && now - heartbeatAt <= staleAfter) {
                    throw new IllegalStateException("NODE_ID 冲突：节点 " + props.nodeId()
                            + " 仍有一个存活实例（最近心跳 " + (now - heartbeatAt)
                            + "ms 前）。请为每个节点配置唯一且稳定的 NODE_ID。");
                }

                // 上一世残留（崩溃未清理）：CAS 删除后重试抢占
                Long removed = redis.execute(COMPARE_AND_DEL, List.of(key), existing);
                log.warn("清理同名节点残留身份: NODE_ID={}, removed={}", props.nodeId(), removed);
            }
            throw new IllegalStateException("NODE_ID 抢占失败（重试耗尽）: " + props.nodeId());
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            if (!allowDegrade) {
                throw new IllegalStateException("无法完成节点身份注册（Redis 不可用）", ex);
            }
            log.warn("无法完成节点身份注册（降级模式继续）: {}", ex.getMessage());
        }
    }

    /** 心跳：仅续期自己的注册（前缀校验）；Redis 重启丢 key 后尝试重新抢占。 */
    @Scheduled(fixedDelayString = "${app.cluster.heartbeat-interval-ms:10000}")
    public void heartbeat() {
        if (!claimed) {
            tryReclaim();
            return;
        }
        try {
            Long renewed = redis.execute(COMPARE_PREFIX_AND_SET,
                    List.of(NODE_PREFIX + props.nodeId()),
                    instanceId + "|",
                    value(System.currentTimeMillis()),
                    String.valueOf(props.nodeTtlSeconds()));
            if (renewed == null || renewed == 0) {
                claimed = false;
                log.error("节点身份 key 丢失或被接管，尝试重新注册: NODE_ID={}", props.nodeId());
                tryReclaim();
            }
        } catch (Exception ex) {
            if (!allowDegrade) {
                log.error("节点心跳失败（失败关闭模式）: {}", ex.getMessage());
            } else {
                log.warn("节点心跳失败: {}", ex.getMessage());
            }
        }
    }

    private void tryReclaim() {
        try {
            claimNodeId();
        } catch (Exception ex) {
            log.error("节点身份重新注册失败: NODE_ID={}, {}", props.nodeId(), ex.getMessage());
        }
    }

    /**
     * 清理本节点上一世残留：
     * - {@code cluster:battle:*} 值 == 本节点 ID（本进程刚启动，不可能还有本地房间）
     * - {@code cluster:userbattle:*} 指向上述战斗、或指向本节点但战斗 key 已不存在
     * - {@code cluster:route:*} 值 == 本节点 ID（进程重启后本地已无任何会话）
     */
    private void cleanupStaleState() {
        Set<String> staleBattles = new HashSet<>();
        int battleDeleted = 0;
        for (String key : scan(BATTLE_PREFIX + "*")) {
            String battleId = key.substring(BATTLE_PREFIX.length());
            try {
                String home = redis.opsForValue().get(key);
                if (props.nodeId().equals(home)) {
                    redis.execute(COMPARE_AND_DEL, List.of(key), home);
                    staleBattles.add(battleId);
                    battleDeleted++;
                }
            } catch (Exception ex) {
                softFail("清理残留战斗路由", ex);
            }
        }

        int userBattleDeleted = 0;
        for (String key : scan(USER_BATTLE_PREFIX + "*")) {
            try {
                String battleId = redis.opsForValue().get(key);
                if (battleId == null) {
                    continue;
                }
                boolean pointsToStale = staleBattles.contains(battleId);
                boolean battleKeyMissing = !Boolean.TRUE.equals(redis.hasKey(BATTLE_PREFIX + battleId));
                if (pointsToStale || battleKeyMissing) {
                    redis.execute(COMPARE_AND_DEL, List.of(key), battleId);
                    userBattleDeleted++;
                }
            } catch (Exception ex) {
                softFail("清理残留 user-battle 路由", ex);
            }
        }

        int routeDeleted = 0;
        for (String key : scan(ROUTE_PREFIX + "*")) {
            try {
                String node = redis.opsForValue().get(key);
                if (props.nodeId().equals(node)) {
                    redis.execute(COMPARE_AND_DEL, List.of(key), node);
                    routeDeleted++;
                }
            } catch (Exception ex) {
                softFail("清理残留用户路由", ex);
            }
        }

        if (battleDeleted > 0 || userBattleDeleted > 0 || routeDeleted > 0) {
            log.warn("已清理本节点上一世残留: battle={}, userBattle={}, route={}",
                    battleDeleted, userBattleDeleted, routeDeleted);
        } else {
            log.info("本节点无上一世残留路由");
        }
    }

    private List<String> scan(String pattern) {
        List<String> keys = new ArrayList<>();
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(200).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        } catch (Exception ex) {
            softFail("SCAN " + pattern, ex);
        }
        return keys;
    }

    /** 存活节点列表（含本节点）。 */
    public List<ClusterApiView> liveNodes() {
        List<ClusterApiView> nodes = new ArrayList<>();
        boolean selfSeen = false;
        try (var cursor = redis.scan(ScanOptions.scanOptions()
                .match(NODE_PREFIX + "*").count(100).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String nodeId = key.substring(NODE_PREFIX.length());
                String value = redis.opsForValue().get(key);
                long startedAtMs = startedAtOf(value);
                nodes.add(new ClusterApiView(nodeId, startedAtMs));
                if (nodeId.equals(props.nodeId())) {
                    selfSeen = true;
                }
            }
        } catch (Exception ex) {
            if (!allowDegrade) {
                throw new IllegalStateException("列出集群节点失败（失败关闭模式）", ex);
            }
            log.warn("列出集群节点失败: {}", ex.getMessage());
        }
        if (!selfSeen) {
            nodes.add(new ClusterApiView(props.nodeId(), startedAt));
        }
        return nodes;
    }

    @PreDestroy
    public void releaseNodeId() {
        if (!claimed) {
            return;
        }
        try {
            redis.execute(COMPARE_PREFIX_AND_DEL,
                    List.of(NODE_PREFIX + props.nodeId()), instanceId + "|");
            log.info("已释放节点身份: NODE_ID={}", props.nodeId());
        } catch (Exception ex) {
            log.warn("释放节点身份失败: {}", ex.getMessage());
        }
    }

    private String value(long heartbeatAt) {
        return instanceId + "|" + startedAt + "|" + heartbeatAt + "|" + HOST;
    }

    private static String hostOf(String value) {
        if (value == null) {
            return "";
        }
        String[] parts = value.split("\\|");
        return parts.length >= 4 ? parts[3] : "";
    }

    private static long heartbeatAt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String[] parts = value.split("\\|");
        if (parts.length >= 3) {
            try {
                return Long.parseLong(parts[2]);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        // 旧格式（纯 startedAt）没有心跳时间：按残留处理
        return 0;
    }

    private static long startedAtOf(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String[] parts = value.split("\\|");
        try {
            return Long.parseLong(parts.length >= 2 ? parts[1] : parts[0]);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void softFail(String op, Exception ex) {
        if (!allowDegrade) {
            throw new IllegalStateException("集群协调失败: " + op, ex);
        }
        log.warn("集群协调失败（降级模式）: op={}, {}", op, ex.getMessage());
    }

    /** 供 ClusterApi.NodeView 的内部投影。 */
    public record ClusterApiView(String nodeId, long startedAtEpochMs) {
        public Instant startedAt() {
            return Instant.ofEpochMilli(startedAtEpochMs);
        }
    }
}
