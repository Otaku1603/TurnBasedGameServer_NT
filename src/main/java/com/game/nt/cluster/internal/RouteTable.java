package com.game.nt.cluster.internal;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 路由表（Redis）：
 * <ul>
 *   <li>{@code cluster:route:{userId} -> nodeId}：用户会话归属，登录写入/断开删除，TTL 兜底</li>
 *   <li>{@code cluster:battle:{battleId} -> nodeId}：战斗归属（战斗状态所在节点）</li>
 * </ul>
 *
 * <p>所有操作 fail-soft：Redis 不可用时记日志并返回"无路由"，本地功能不受影响。
 */
@Component
public class RouteTable {

    private static final String ROUTE_PREFIX = "cluster:route:";
    private static final String BATTLE_PREFIX = "cluster:battle:";
    private static final String USER_BATTLE_PREFIX = "cluster:userbattle:";

    /** 仅当值仍等于期望节点时删除（Lua 保证原子，防顶号场景误删新路由） */
    private static final DefaultRedisScript<Long> COMPARE_AND_DEL = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ClusterProperties props;

    public RouteTable(StringRedisTemplate redis, ClusterProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void bindUser(long userId) {
        try {
            redis.opsForValue().set(ROUTE_PREFIX + userId, props.nodeId(),
                    Duration.ofHours(props.routeTtlHours()));
        } catch (Exception ex) {
            softFail("bindUser", ex);
        }
    }

    public void unbindUser(long userId) {
        try {
            redis.execute(COMPARE_AND_DEL, List.of(ROUTE_PREFIX + userId), props.nodeId());
        } catch (Exception ex) {
            softFail("unbindUser", ex);
        }
    }

    public String nodeOfUser(long userId) {
        try {
            return redis.opsForValue().get(ROUTE_PREFIX + userId);
        } catch (Exception ex) {
            softFail("nodeOfUser", ex);
            return null;
        }
    }

    public void bindBattle(String battleId) {
        try {
            redis.opsForValue().set(BATTLE_PREFIX + battleId, props.nodeId(),
                    Duration.ofHours(props.battleTtlHours()));
        } catch (Exception ex) {
            softFail("bindBattle", ex);
        }
    }

    public String nodeOfBattle(String battleId) {
        try {
            return redis.opsForValue().get(BATTLE_PREFIX + battleId);
        } catch (Exception ex) {
            softFail("nodeOfBattle", ex);
            return null;
        }
    }

    public void bindUserBattle(long userId, String battleId) {
        try {
            redis.opsForValue().set(USER_BATTLE_PREFIX + userId, battleId,
                    Duration.ofHours(props.battleTtlHours()));
        } catch (Exception ex) {
            softFail("bindUserBattle", ex);
        }
    }

    /**
     * 解除"用户进行中的战斗"登记。
     *
     * <p>用 COMPARE_AND_DEL 而非直接删除：玩家结束上一局后立刻进入新一局时，
     * 旧局清理晚于新局登记，直接删会把新局的映射误删，导致跨节点断线/重连路由失效。
     */
    public void unbindUserBattle(long userId, String battleId) {
        try {
            redis.execute(COMPARE_AND_DEL, List.of(USER_BATTLE_PREFIX + userId), battleId);
        } catch (Exception ex) {
            softFail("unbindUserBattle", ex);
        }
    }

    public String battleOfUser(long userId) {
        try {
            return redis.opsForValue().get(USER_BATTLE_PREFIX + userId);
        } catch (Exception ex) {
            softFail("battleOfUser", ex);
            return null;
        }
    }

    /** 全集群路由在线用户（GM 全服邮件等低频操作使用；SCAN 不阻塞）。 */
    public List<Long> routedUserIds() {
        List<Long> ids = new ArrayList<>();
        try (var cursor = redis.scan(org.springframework.data.redis.core.ScanOptions
                .scanOptions().match(ROUTE_PREFIX + "*").count(200).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String userId = key.substring(ROUTE_PREFIX.length());
                try {
                    ids.add(Long.parseLong(userId));
                } catch (NumberFormatException ignored) {
                    // 非 userId 命名的 key，跳过
                }
            }
        } catch (Exception ex) {
            softFail("routedUserIds", ex);
        }
        return ids;
    }

    private void softFail(String op, Exception ex) {
        org.slf4j.LoggerFactory.getLogger(RouteTable.class)
                .warn("集群路由操作失败（降级为本地模式）: op={}", op, ex);
    }
}
