package com.game.nt.cluster.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 节点注册表：每节点一个 TTL key（{@code cluster:node:{id}}），
 * 心跳续期；失联超过 TTL 即视为下线。可用 SCAN 列出存活节点（GM 集群视图）。
 */
@Component
public class NodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(NodeRegistry.class);
    private static final String NODE_PREFIX = "cluster:node:";

    private final StringRedisTemplate redis;
    private final ClusterProperties props;
    private final long startedAt = System.currentTimeMillis();

    public NodeRegistry(StringRedisTemplate redis, ClusterProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** 心跳：续期自身存活 key（周期为 TTL 的 1/3）。 */
    @Scheduled(fixedDelayString = "${app.cluster.heartbeat-interval-ms:10000}")
    public void heartbeat() {
        try {
            redis.opsForValue().set(NODE_PREFIX + props.nodeId(),
                    String.valueOf(startedAt), Duration.ofSeconds(props.nodeTtlSeconds()));
        } catch (Exception ex) {
            log.warn("节点心跳失败: {}", ex.getMessage());
        }
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
                String startedAtStr = redis.opsForValue().get(key);
                long startedAtMs = startedAtStr == null ? 0 : Long.parseLong(startedAtStr);
                nodes.add(new ClusterApiView(nodeId, startedAtMs));
                if (nodeId.equals(props.nodeId())) {
                    selfSeen = true;
                }
            }
        } catch (Exception ex) {
            log.warn("列出集群节点失败: {}", ex.getMessage());
        }
        if (!selfSeen) {
            nodes.add(new ClusterApiView(props.nodeId(), startedAt));
        }
        return nodes;
    }

    /** 供 ClusterApi.NodeView 的内部投影。 */
    public record ClusterApiView(String nodeId, long startedAtEpochMs) {
        public Instant startedAt() {
            return Instant.ofEpochMilli(startedAtEpochMs);
        }
    }
}
