package com.game.nt.match.internal;

import com.game.nt.battle.BattleApi;
import com.game.nt.shared.push.ClientPushApi;
import com.game.nt.identity.IdentityApi;
import com.game.nt.match.MatchApi;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.risk.RiskApi;
import com.game.nt.proto.GameProto.MatchSuccessResponse;
import com.game.nt.proto.GameProto.MessageType;
import com.game.nt.proto.GameProto.PlayerInfo;
import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 匹配服务。
 *
 * <p>相对原项目的改进：
 * <ul>
 *   <li>队列用 <b>Redis ZSET</b>（member = userId，score = 入队时间）：
 *       {@code ZADD} 天然去重，根治原实现"同一玩家可重复入队"</li>
 *   <li>出队用 {@code ZREM}，原子操作，不再有 read-modify-write</li>
 *   <li>定时任务加 <b>分布式锁</b>，多实例下只有一个节点执行匹配</li>
 *   <li>匹配范围为动态扩大的 Elo 区间，等待越久范围越大</li>
 * </ul>
 */
@Service
public class MatchService implements MatchApi {

    private static final Logger log = LoggerFactory.getLogger(MatchService.class);

    private static final String QUEUE_KEY = "match:queue";
    private static final String LOCK_KEY = "lock:match";
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    /** 安全释放锁：仅当锁值仍等于本节点的 token 时才删除（防超时后误删他人的锁）。 */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> SAFE_UNLOCK =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        return redis.call('del', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    /** 基础 Elo 匹配范围；每等待 10 秒扩大 50，上限 500 */
    private static final int BASE_ELO_RANGE = 100;
    private static final int RANGE_GROWTH_PER_10S = 50;
    private static final int MAX_ELO_RANGE = 500;
    private static final long QUEUE_TIMEOUT_SECONDS = 120;

    private final StringRedisTemplate redis;
    private final BattleApi battleApi;
    private final IdentityApi identityApi;
    private final ClientPushApi clientPushApi;
    private final RiskApi riskApi;
    private final com.game.nt.cluster.ClusterApi clusterApi;
    private final com.game.nt.shared.lua.LuaSandbox matchLuaSandbox;

    public MatchService(StringRedisTemplate redis, BattleApi battleApi,
                        IdentityApi identityApi, ClientPushApi clientPushApi, RiskApi riskApi,
                        com.game.nt.cluster.ClusterApi clusterApi,
                        @org.springframework.beans.factory.annotation.Qualifier("matchLuaSandbox")
                        com.game.nt.shared.lua.LuaSandbox matchLuaSandbox) {
        this.redis = redis;
        this.battleApi = battleApi;
        this.identityApi = identityApi;
        this.clientPushApi = clientPushApi;
        this.riskApi = riskApi;
        this.clusterApi = clusterApi;
        this.matchLuaSandbox = matchLuaSandbox;
    }

    /**
     * 是否已有"仍然有效"的战斗。
     *
     * <p>本机内存（{@code battleApi}）只覆盖归属本节点的对局；玩家在别的节点打时本机查不到，
     * 会造成跨节点重复匹配。因此叠加 Redis 登记表判断，并检查归属节点是否仍存活——
     * 节点崩溃后遗留的对局登记（TTL 24h）是残留，不能把玩家永久挡在匹配之外。
     */
    private boolean inActiveBattle(long userId) {
        if (battleApi.inBattle(userId)) {
            return true;
        }
        String battleId = clusterApi.battleOfUser(userId);
        if (battleId == null) {
            return false;
        }
        String home = clusterApi.nodeOfBattle(battleId);
        if (home == null || home.equals(clusterApi.selfNodeId())) {
            // 归属未知或为本节点：本机已无该房间 → 残留登记
            return false;
        }
        return liveNodeIds().contains(home);
    }

    private Set<String> liveNodeIds() {
        try {
            List<com.game.nt.cluster.ClusterApi.NodeView> nodes = clusterApi.liveNodes();
            if (nodes == null) {
                return Set.of();
            }
            return nodes.stream().map(com.game.nt.cluster.ClusterApi.NodeView::nodeId)
                    .collect(java.util.stream.Collectors.toSet());
        } catch (Exception ex) {
            log.debug("集群节点列表获取失败（按无跨节点对局处理）: {}", ex.getMessage());
            return Set.of();
        }
    }

    public void join(long userId) {
        riskApi.onMatchJoin(userId); // 限流：防脚本刷匹配队列（WS/HTTP 双通道统一入口）
        IdentityApi.AccountInfo account = identityApi.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "玩家不存在"));
        if (account.banned()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被封禁");
        }
        if (inActiveBattle(userId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "你正在战斗中");
        }
        redis.opsForZSet().add(QUEUE_KEY, String.valueOf(userId), System.currentTimeMillis());
        log.info("加入匹配队列: userId={}, elo={}", userId, account.eloRating());
    }

    public boolean cancel(long userId) {
        Long removed = redis.opsForZSet().remove(QUEUE_KEY, String.valueOf(userId));
        return removed != null && removed > 0;
    }

    /** 定时撮合：加分布式锁，保证多实例下只有一个节点执行。 */
    @Scheduled(fixedDelay = 2000)
    public void tryMatch() {
        String token = java.util.UUID.randomUUID().toString();
        Boolean locked;
        try {
            locked = redis.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_TTL);
        } catch (Exception ex) {
            // Redis 异常时降级：本轮不撮合（避免抛异常刷满日志），下一轮自动重试
            log.warn("匹配锁获取失败（Redis 异常），本轮跳过: {}", ex.getMessage());
            return;
        }
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        try {
            doMatch();
        } catch (Exception ex) {
            log.error("匹配任务异常", ex);
        } finally {
            // token 校验释放：即使本轮执行超过 TTL（锁已易主），也不会误删他人的锁
            try {
                redis.execute(SAFE_UNLOCK, java.util.List.of(LOCK_KEY), token);
            } catch (Exception ex) {
                log.warn("匹配锁释放失败: {}", ex.getMessage());
            }
        }
    }

    private void doMatch() {
        Set<String> members = redis.opsForZSet().range(QUEUE_KEY, 0, -1);
        if (members == null || members.size() < 2) {
            return;
        }

        long now = System.currentTimeMillis();
        List<Candidate> candidates = new ArrayList<>();
        for (String member : members) {
            long userId = Long.parseLong(member);
            Double score = redis.opsForZSet().score(QUEUE_KEY, member);
            long enqueuedAt = score == null ? now : score.longValue();
            long waitedSeconds = (now - enqueuedAt) / 1000;

            if (waitedSeconds > QUEUE_TIMEOUT_SECONDS) {
                redis.opsForZSet().remove(QUEUE_KEY, member);
                notifyMatchTimeout(userId);
                log.info("匹配超时移除: userId={}", userId);
                continue;
            }
            IdentityApi.AccountInfo account = identityApi.findByUserId(userId).orElse(null);
            if (account == null || inActiveBattle(userId)) {
                redis.opsForZSet().remove(QUEUE_KEY, member);
                continue;
            }
            candidates.add(new Candidate(userId, account.eloRating(), account.nickname(), waitedSeconds));
        }

        // 等待越久越优先
        candidates.sort(Comparator.comparingLong(Candidate::waitedSeconds).reversed());

        boolean[] used = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            if (used[i]) {
                continue;
            }
            Candidate first = candidates.get(i);
            int range = dynamicRange(first.waitedSeconds());

            for (int j = i + 1; j < candidates.size(); j++) {
                if (used[j]) {
                    continue;
                }
                Candidate second = candidates.get(j);
                if (Math.abs(first.eloRating() - second.eloRating()) > range) {
                    continue;
                }
                if (startBattle(first, second)) {
                    used[i] = true;
                    used[j] = true;
                    break; // 配对成功，first 已出局
                }
                // 失败（出队竞态）：不 break，first 尝试下一候选
            }
        }
    }

    private boolean startBattle(Candidate a, Candidate b) {
        // 原子出队：谁先移除成功谁负责开局（避免重复配对）
        Long removedA = redis.opsForZSet().remove(QUEUE_KEY, String.valueOf(a.userId()));
        Long removedB = redis.opsForZSet().remove(QUEUE_KEY, String.valueOf(b.userId()));
        if (removedA == null || removedA == 0 || removedB == null || removedB == 0) {
            // 只回补被本节点成功移除的一方：另一方仍留在队列（可能已被其他节点配对）
            if (removedA != null && removedA > 0) {
                requeue(a);
            }
            if (removedB != null && removedB > 0) {
                requeue(b);
            }
            return false;
        }

        try {
            String battleId = battleApi.createBattle(a.userId(), b.userId(), a.nickname(), b.nickname());
            pushMatchSuccess(a, b, battleId);
            pushMatchSuccess(b, a, battleId);
            log.info("匹配成功: {} vs {}, battleId={}", a.userId(), b.userId(), battleId);
            return true;
        } catch (Exception ex) {
            // 开局失败（如 DB 瞬时异常）：双方回补队列，否则玩家出队后无人通知、永久等待
            log.error("开局失败，双方回补队列: {} vs {}", a.userId(), b.userId(), ex);
            requeue(a);
            requeue(b);
            return false;
        }
    }

    /** 回补队列：保留原等待时长对应的入队时间，避免"被重置等待时间"。 */
    private void requeue(Candidate candidate) {
        try {
            long score = System.currentTimeMillis() - candidate.waitedSeconds() * 1000L;
            redis.opsForZSet().add(QUEUE_KEY, String.valueOf(candidate.userId()), score);
        } catch (Exception ex) {
            log.warn("回补匹配队列失败: userId={}, {}", candidate.userId(), ex.getMessage());
        }
    }

    /** 匹配超时移除时通知客户端（否则客户端停留在"匹配中"直到重启）。 */
    private void notifyMatchTimeout(long userId) {
        try {
            clientPushApi.sendToUser(userId, GameMessage.newBuilder()
                    .setType(MessageType.ERROR)
                    .setErrorResponse(com.game.nt.proto.GameProto.ErrorResponse.newBuilder()
                            .setCode(com.game.nt.proto.GameProto.ErrorCode.NOT_FOUND)
                            .setMessage("匹配超时，请重新匹配")
                            .build())
                    .build());
        } catch (Exception ex) {
            // 通知失败不影响清理流程
            log.debug("匹配超时通知失败: userId={}, {}", userId, ex.getMessage());
        }
    }

    private void pushMatchSuccess(Candidate self, Candidate opponent, String battleId) {
        clientPushApi.sendToUser(self.userId(), GameMessage.newBuilder()
                .setType(MessageType.MATCH_SUCCESS)
                .setMatchSuccessResponse(MatchSuccessResponse.newBuilder()
                        .setBattleId(battleId)
                        .setOpponent(PlayerInfo.newBuilder()
                                .setUserId(opponent.userId())
                                .setNickname(opponent.nickname())
                                .setEloRating(opponent.eloRating())
                                .build())
                        .build())
                .build());
    }

    /**
     * 动态匹配区间：优先调用 Lua 策略脚本（{@code lua/match_strategy.lua}，可经管理端热更），
     * 沙箱未就绪/脚本异常时降级到 Java 兜底公式（与脚本默认参数一致）。
     */
    private int dynamicRange(long waitedSeconds) {
        try {
            long range = matchLuaSandbox.callForLong("match_range", waitedSeconds);
            if (range > 0) {
                return (int) Math.min(range, MAX_ELO_RANGE);
            }
        } catch (Exception ex) {
            log.debug("匹配策略 Lua 调用失败，使用 Java 兜底: {}", ex.getMessage());
        }
        int extra = (int) (waitedSeconds / 10) * RANGE_GROWTH_PER_10S;
        return Math.min(BASE_ELO_RANGE + extra, MAX_ELO_RANGE);
    }

    public int queueSize() {
        Long size = redis.opsForZSet().zCard(QUEUE_KEY);
        return size == null ? 0 : size.intValue();
    }

    private record Candidate(long userId, int eloRating, String nickname, long waitedSeconds) {
    }
}
