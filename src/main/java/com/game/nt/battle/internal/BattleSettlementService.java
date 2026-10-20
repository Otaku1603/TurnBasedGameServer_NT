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
 * 战斗结算（独立 Bean → {@code @Transactional} 生效；原项目把结算写在 BattleService 内部，
 * 且用 {@code @Async} 同类自调用，代理失效导致同步跑在 Netty I/O 线程）。
 *
 * <p>幂等三重保险：
 * <ol>
 *   <li>幂等键 {@code battle:settle:{battleId}}（先占位）</li>
 *   <li>{@code t_battle_record.battle_id} 唯一索引</li>
 *   <li>全程单事务，任一步失败整体回滚</li>
 * </ol>
 *
 * <p>奖励发放（邮件/任务进度）不在此事务内直接调用，而是发布 {@link BattleSettledEvent}——
 * 事件随本事务持久化到事件发布注册表（事务性发件箱），提交后异步派发、失败重试。
 * 事务只保留必须强一致的写入（Elo、战报、败者安慰金）。
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

    public BattleSettlementService(BattleSettlementMapper mapper,
                                   IdentityApi identityApi,
                                   IdempotencyService idempotencyService,
                                   ApplicationEventPublisher eventPublisher,
                                   com.game.nt.cluster.ClusterApi clusterApi) {
        this.mapper = mapper;
        this.identityApi = identityApi;
        this.idempotencyService = idempotencyService;
        this.eventPublisher = eventPublisher;
        this.clusterApi = clusterApi;
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

    /** 战报日志：手工拼 JSON 数组，避免为空场景引入额外依赖。 */
    private static String toJsonLog(BattleRoom room) {
        StringBuilder sb = new StringBuilder("[");
        List<String> logs = room.logs();
        for (int i = 0; i < logs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(logs.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }
}
