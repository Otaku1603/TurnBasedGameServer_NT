package com.game.nt.battle.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 断线/挂机超时判负。
 *
 * <p>断线玩家不会立刻判负，而是给一个重连窗口；窗口内未回来才判负。
 * 判负动作同样通过房间 mailbox 投递，保证状态串行变更。
 *
 * <p>多实例部署时需加分布式锁（路线图 A9）。
 */
@Component
public class BattleTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(BattleTimeoutScheduler.class);

    private final BattleManager battleManager;

    /** 重连窗口（秒），默认 120。 */
    @Value("${app.battle.reconnect-timeout-seconds:120}")
    private long reconnectTimeoutSeconds;

    /** WAITING 房间解散窗口（秒），默认 30。 */
    @Value("${app.battle.waiting-timeout-seconds:30}")
    private long waitingTimeoutSeconds;

    /** 回合内操作时限（秒），默认 30；与客户端 TurnTimeLimit 倒计时对齐。 */
    @Value("${app.battle.turn-timeout-seconds:30}")
    private long turnTimeoutSeconds;

    public BattleTimeoutScheduler(BattleManager battleManager) {
        this.battleManager = battleManager;
    }

    @Scheduled(fixedDelay = 5000)
    public void judge() {
        int judged = battleManager.judgeTimeouts(reconnectTimeoutSeconds * 1000);
        if (judged > 0) {
            log.info("超时判负完成，本次处理 {} 场", judged);
        }
        int swept = battleManager.sweepWaitingRooms(waitingTimeoutSeconds * 1000);
        if (swept > 0) {
            log.info("WAITING 房间回收完成，本次解散 {} 场", swept);
        }
        int turnExpired = battleManager.judgeTurnTimeouts(turnTimeoutSeconds);
        if (turnExpired > 0) {
            log.info("回合超时判负完成，本次处理 {} 场", turnExpired);
        }
        int settled = battleManager.retryPendingSettlements();
        if (settled > 0) {
            log.info("失败结算重试完成，本次补结算 {} 场", settled);
        }
    }
}
