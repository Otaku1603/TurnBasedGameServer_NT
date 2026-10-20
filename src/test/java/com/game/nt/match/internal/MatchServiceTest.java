package com.game.nt.match.internal;

import com.game.nt.battle.BattleApi;
import com.game.nt.identity.IdentityApi;
import com.game.nt.risk.RiskApi;
import com.game.nt.shared.lua.LuaSandbox;
import com.game.nt.shared.push.ClientPushApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 匹配服务单元测试：ZSET 去重语义、原子出队防重复配对、动态 Elo 区间。
 */
class MatchServiceTest {

    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zSet;
    private BattleApi battleApi;
    private IdentityApi identityApi;
    private ClientPushApi clientPushApi;
    private RiskApi riskApi;
    private com.game.nt.cluster.ClusterApi clusterApi;
    private MatchService matchService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        zSet = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zSet);
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.setIfAbsent(eq("lock:match"), anyString(), eq(java.time.Duration.ofSeconds(5))))
                .thenReturn(true);
        battleApi = mock(BattleApi.class);
        identityApi = mock(IdentityApi.class);
        clientPushApi = mock(ClientPushApi.class);
        riskApi = mock(RiskApi.class);
        // 集群登记默认无记录（battleOfUser → null）：跨节点判断短路，不影响本机用例
        clusterApi = mock(com.game.nt.cluster.ClusterApi.class);
        // Lua 沙箱 mock：callForLong 抛异常 → 走 Java 兜底公式（与生产降级路径一致）
        LuaSandbox matchLua = mock(LuaSandbox.class);
        when(matchLua.callForLong(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object>any()))
                .thenThrow(new IllegalStateException("lua unavailable"));
        matchService = new MatchService(redis, battleApi, identityApi, clientPushApi, riskApi, clusterApi, matchLua);
    }

    private IdentityApi.AccountInfo account(long id, String nickname, int elo) {
        return new IdentityApi.AccountInfo(id, "u" + id, nickname, elo, 0L, 0, 0, false);
    }

    private void enqueue(String... userIds) {
        Set<String> members = new LinkedHashSet<>(List.of(userIds));
        when(zSet.range("match:queue", 0, -1)).thenReturn(members);
        for (String uid : userIds) {
            when(zSet.score("match:queue", uid)).thenReturn((double) System.currentTimeMillis());
            long id = Long.parseLong(uid);
            lenient().doReturn(1L).when(zSet).remove("match:queue", uid);
        }
    }

    @Test
    @DisplayName("队列人数不足 2：不撮合")
    void noMatchWhenFewerThanTwo() {
        when(zSet.range("match:queue", 0, -1)).thenReturn(Set.of("1"));
        matchService.tryMatch();
        verify(battleApi, never()).createBattle(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("锁定失败（另一实例持有）：本节点跳过撮合")
    void skippedWhenLockHeld() {
        when(redis.opsForValue().setIfAbsent(eq("lock:match"), anyString(), any(java.time.Duration.class))).thenReturn(false);
        matchService.tryMatch();
        verify(zSet, never()).range(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("Elo 差距超区间不开局，区间内则配对并双方推送 MATCH_SUCCESS")
    void pairsWithinEloRange() {
        // 两个刚入队、Elo 相差 50（< 基础区间 100）的玩家
        enqueue("1", "2");
        when(identityApi.findByUserId(1L)).thenReturn(Optional.of(account(1L, "A", 1000)));
        when(identityApi.findByUserId(2L)).thenReturn(Optional.of(account(2L, "B", 1050)));
        when(zSet.remove("match:queue", "1")).thenReturn(1L);
        when(zSet.remove("match:queue", "2")).thenReturn(1L);
        when(battleApi.createBattle(1L, 2L, "A", "B")).thenReturn("b-1");

        matchService.tryMatch();

        verify(battleApi).createBattle(1L, 2L, "A", "B");
        verify(clientPushApi).sendToUser(eq(1L), any());
        verify(clientPushApi).sendToUser(eq(2L), any());
    }

    @Test
    @DisplayName("Elo 差距超基础区间且未等待：不配对")
    void notPairedWhenEloTooFar() {
        enqueue("1", "2");
        when(identityApi.findByUserId(1L)).thenReturn(Optional.of(account(1L, "A", 500)));
        when(identityApi.findByUserId(2L)).thenReturn(Optional.of(account(2L, "B", 1500)));

        matchService.tryMatch();

        verify(battleApi, never()).createBattle(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("出队失败（已被其他节点配对）：不再开局")
    void noBattleWhenDequeueFails() {
        enqueue("1", "2");
        when(identityApi.findByUserId(1L)).thenReturn(Optional.of(account(1L, "A", 1000)));
        when(identityApi.findByUserId(2L)).thenReturn(Optional.of(account(2L, "B", 1000)));
        // 另一节点已把 2 号原子移除
        when(zSet.remove("match:queue", "2")).thenReturn(0L);

        matchService.tryMatch();

        verify(battleApi, never()).createBattle(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("战斗中/账号不存在：被移出队列")
    void inBattlePlayersRemoved() {
        enqueue("1", "2");
        when(identityApi.findByUserId(1L)).thenReturn(Optional.of(account(1L, "A", 1000)));
        when(identityApi.findByUserId(2L)).thenReturn(Optional.of(account(2L, "B", 1000)));
        when(battleApi.inBattle(1L)).thenReturn(true);
        when(battleApi.inBattle(2L)).thenReturn(false);
        when(zSet.remove("match:queue", "2")).thenReturn(1L);
        when(battleApi.createBattle(anyLong(), anyLong(), anyString(), anyString())).thenReturn("b-9");

        matchService.tryMatch();

        verify(zSet).remove("match:queue", "1");
        // 1 号在战斗中被移除，只剩 2 号，无法成局
        verify(battleApi, never()).createBattle(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("开局失败：双方回补队列（不会被静默丢弃）")
    void requeuesBothWhenCreateBattleFails() {
        enqueue("1", "2");
        when(identityApi.findByUserId(1L)).thenReturn(Optional.of(account(1L, "A", 1000)));
        when(identityApi.findByUserId(2L)).thenReturn(Optional.of(account(2L, "B", 1000)));
        when(zSet.remove("match:queue", "1")).thenReturn(1L);
        when(zSet.remove("match:queue", "2")).thenReturn(1L);
        when(battleApi.createBattle(1L, 2L, "A", "B")).thenThrow(new RuntimeException("db down"));

        matchService.tryMatch();

        verify(zSet).add(eq("match:queue"), eq("1"), anyDouble());
        verify(zSet).add(eq("match:queue"), eq("2"), anyDouble());
    }

    @Test
    @DisplayName("出队部分失败：只回补本节点成功移除的一方")
    void requeuesOnlyRemovedSideWhenPartialDequeue() {
        enqueue("1", "2");
        when(identityApi.findByUserId(1L)).thenReturn(Optional.of(account(1L, "A", 1000)));
        when(identityApi.findByUserId(2L)).thenReturn(Optional.of(account(2L, "B", 1000)));
        when(zSet.remove("match:queue", "1")).thenReturn(1L);
        when(zSet.remove("match:queue", "2")).thenReturn(0L);

        matchService.tryMatch();

        verify(zSet).add(eq("match:queue"), eq("1"), anyDouble());
        verify(zSet, never()).add(eq("match:queue"), eq("2"), anyDouble());
    }
}
