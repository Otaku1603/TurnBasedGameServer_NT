package com.game.nt.quest.internal;

import com.game.nt.economy.EconomyApi;
import com.game.nt.identity.IdentityApi;
import com.game.nt.quest.QuestApi;
import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 任务/成就服务。
 *
 * <p>并发要点：奖励领取用<b>条件 UPDATE</b>（{@code completed = 1 AND reward_claimed = 0}），
 * 返回 1 才发放 —— 并发点两次也只发一次，与原项目"幂等检查放最后"的写法相反。
 */
@Service
public class QuestService implements QuestApi {

    private static final Logger log = LoggerFactory.getLogger(QuestService.class);

    private final QuestMapper questMapper;
    private final IdentityApi identityApi;
    private final EconomyApi economyApi;

    public QuestService(QuestMapper questMapper, IdentityApi identityApi, EconomyApi economyApi) {
        this.questMapper = questMapper;
        this.identityApi = identityApi;
        this.economyApi = economyApi;
    }

    @Override
    @Transactional
    public void addProgress(long userId, String code, int delta) {
        if (delta <= 0 || code == null || code.isBlank()) {
            return;
        }
        if (questMapper.addProgress(userId, code, delta) > 0) {
            int completed = questMapper.markCompleted(userId, code);
            if (completed > 0) {
                log.info("任务达成: userId={}, code={}", userId, code);
            }
        }
    }

    public List<Map<String, Object>> listMine(long userId) {
        return questMapper.listWithProgress(userId);
    }

    @Transactional
    public Map<String, Object> claimReward(long userId, int questId) {
        // 条件 UPDATE 抢占：返回 1 表示本次是"首次且合法"的领取
        if (questMapper.markRewardClaimed(userId, questId) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务未完成或奖励已领取");
        }
        long gold = questMapper.rewardGoldOf(questId) == null ? 0L : questMapper.rewardGoldOf(questId);
        if (gold > 0) {
            identityApi.addGold(userId, gold);
        }
        Integer itemId = questMapper.rewardItemIdOf(questId);
        Integer itemAmount = questMapper.rewardItemAmountOf(questId);
        if (itemId != null && itemId > 0 && itemAmount != null && itemAmount > 0) {
            economyApi.grantItem(userId, itemId, itemAmount);
        }
        log.info("任务奖励已领取: userId={}, questId={}, gold={}, item={}x{}",
                userId, questId, gold, itemId, itemAmount);
        return Map.of(
                "gold", gold,
                "itemId", itemId == null ? 0 : itemId,
                "itemAmount", itemAmount == null ? 0 : itemAmount);
    }
}
