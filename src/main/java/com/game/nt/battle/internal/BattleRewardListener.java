package com.game.nt.battle.internal;

import com.game.nt.battle.BattleSettledEvent;
import com.game.nt.mail.MailApi;
import com.game.nt.quest.QuestApi;
import com.game.nt.shared.idempotency.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 战斗结算事件的奖励监听者。
 *
 * <p>{@link ApplicationModuleListener} = AFTER_COMMIT + 异步 + 事件发布注册表跟踪：
 * 结算事务提交后才执行；执行失败事件留在 {@code EVENT_PUBLICATION} 表，重启/重发机制会重试。
 *
 * <p>因为投递语义是 at-least-once，监听者自身必须幂等——
 * 这里用幂等键 {@code battle:reward:{battleId}} 兜底，重复投递不会重复发奖。
 */
@Component
public class BattleRewardListener {

    private static final Logger log = LoggerFactory.getLogger(BattleRewardListener.class);

    private static final int WINNER_MAIL_GOLD = 200;

    private final MailApi mailApi;
    private final QuestApi questApi;
    private final IdempotencyService idempotencyService;

    public BattleRewardListener(MailApi mailApi, QuestApi questApi, IdempotencyService idempotencyService) {
        this.mailApi = mailApi;
        this.questApi = questApi;
        this.idempotencyService = idempotencyService;
    }

    @ApplicationModuleListener
    public void onBattleSettled(BattleSettledEvent event) {
        String key = "battle:reward:" + event.battleId();
        if (!idempotencyService.tryAcquire(key)) {
            log.info("奖励已发放，忽略重复投递: battleId={}", event.battleId());
            idempotencyService.markDone(key, "duplicate");
            return;
        }

        try {
            // 胜者奖励走邮件（需领取）——顺带覆盖邮件附件的完整链路
            mailApi.sendSystemMail(event.winnerId(), "战斗胜利奖励",
                    "你在对局 %s 中获胜，奖励已附上。".formatted(event.battleId()),
                    List.of(MailApi.Attachment.gold(WINNER_MAIL_GOLD)), Duration.ofDays(7));

            // 跨模块联动：胜场驱动任务/成就进度
            questApi.addProgress(event.winnerId(), "WIN_BATTLE", 1);
            questApi.addProgress(event.winnerId(), "WIN_10_BATTLES", 1);

            idempotencyService.markDone(key, "ok");
            log.info("战斗奖励发放完成: battleId={}, winner={}", event.battleId(), event.winnerId());
        } catch (Exception e) {
            // 释放幂等占位，让事件重试机制可以再次投递
            idempotencyService.release(key);
            throw e;
        }
    }
}
