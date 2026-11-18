package com.game.nt.battle.internal;

import com.game.nt.battle.BattleSettledEvent;
import com.game.nt.identity.IdentityApi;
import com.game.nt.proto.GameProto.EndReason;
import com.game.nt.shared.idempotency.IdempotencyService;
import com.game.nt.shared.rating.EloCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 战斗结算：幂等键 + 唯一索引 + 单事务；奖励经 {@link BattleSettledEvent} 发件箱异步派发。
 */
@Service
public class BattleSettlementService {

    private static final Logger log = LoggerFactory.getLogger(BattleSettlementService.class);

    private static final long LOSER_CONSOLATION_GOLD = 20;

    private final BattleSettlementMapper mapper;
    private final IdentityApi identityApi;
    private final IdempotencyService idempotencyService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.game.nt.cluster.ClusterApi clusterApi;
    /**
     * 战报 JSON 序列化统一使用 Spring Boot 4 默认的 Jackson 3（{@code tools.jackson}）。
     * 此前注入 Jackson 2 的 {@code com.fasterxml.jackson.databind.ObjectMapper}，
     * 而 Boot 4 只自动配置 Jackson 3 的 {@code JsonMapper}，导致启动期缺少必需 Bean。
     */
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public BattleSettlementService(BattleSettlementMapper mapper,
                                   IdentityApi identityApi,
                                   IdempotencyService idempotencyService,
                                   ApplicationEventPublisher eventPublisher,
                                   com.game.nt.cluster.ClusterApi clusterApi,
                                   tools.jackson.databind.ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.identityApi = identityApi;
        this.idempotencyService = idempotencyService;
        this.eventPublisher = eventPublisher;
        this.clusterApi = clusterApi;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void settle(BattleRoom room, long winnerId, long loserId, EndReason reason, boolean crossNode) {
        String key = "battle:settle:" + room.battleId();
        String homeNode = clusterApi.selfNodeId();
        if (!idempotencyService.tryAcquire(key)) {
            log.warn("战斗已结算，忽略重复请求: battleId={}", room.battleId());
            return;
        }

        IdentityApi.AccountInfo winner = identityApi.findByUserId(winnerId).orElse(null);
        IdentityApi.AccountInfo loser = identityApi.findByUserId(loserId).orElse(null);
        if (winner == null || loser == null) {
            log.error("结算失败：玩家不存在. battleId={}, winner={}, loser={}",
                    room.battleId(), winnerId, loserId);
            return;
        }

        // 标准 Elo（零和）
        int[] elo = EloCalculator.apply(winner.eloRating(), loser.eloRating(), EloCalculator.DEFAULT_K);

        mapper.updateAfterBattle(winnerId, elo[0], 1, 0L);
        mapper.updateAfterBattle(loserId, elo[1], 0, LOSER_CONSOLATION_GOLD);

        int p1Before;
        int p1After;
        int p2Before;
        int p2After;
        if (room.player1().getUserId() == winnerId) {
            p1Before = winner.eloRating();
            p1After = elo[0];
            p2Before = loser.eloRating();
            p2After = elo[1];
        } else {
            p1Before = loser.eloRating();
            p1After = elo[1];
            p2Before = winner.eloRating();
            p2After = elo[0];
        }

        long startMs = room.startTimeMillis();
        long endMs = room.endTimeMillis() > 0 ? room.endTimeMillis() : System.currentTimeMillis();
        int durationSeconds = (int) Math.max(0, (endMs - startMs) / 1000);

        mapper.insertRecord(room.battleId(),
                room.player1().getUserId(), room.player1().getNickname(), room.player1().getHp(),
                p1Before, p1After,
                room.player2().getUserId(), room.player2().getNickname(), room.player2().getHp(),
                p2Before, p2After,
                winnerId, reason.name(), room.round(), durationSeconds,
                toJsonLog(room),
                toLocalDateTime(startMs), toLocalDateTime(endMs),
                homeNode, crossNode);

        // 事务性发件箱：事件随本事务持久化，提交后异步派发奖励（见 BattleRewardListener）
        eventPublisher.publishEvent(new BattleSettledEvent(
                room.battleId(), winnerId, loserId, reason.name(), homeNode, crossNode));

        idempotencyService.markDone(key, "winner=" + winnerId + ",elo=" + elo[0]);
        log.info("战斗结算完成: battleId={}, winner={}, elo {}/{} -> {}/{}, 跨服={}",
                room.battleId(), winnerId, p1Before, p2Before, p1After, p2After, crossNode);
    }

    /**
     * 战报日志 JSON 化：用 Jackson 序列化 —— 手工拼接只转义 \ 与 "，
     * 昵称若含换行/控制字符会产出非法 JSON；Jackson 对全部控制字符正确转义。
     */
    private String toJsonLog(BattleRoom room) {
        try {
            return objectMapper.writeValueAsString(room.logs());
        } catch (Exception ex) {
            // List<String> 序列化不会失败；防御性兜底
            throw new IllegalStateException("战报日志序列化失败: battleId=" + room.battleId(), ex);
        }
    }

    private static LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }
}
