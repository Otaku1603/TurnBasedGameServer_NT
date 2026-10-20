package com.game.nt.battle.internal;

import com.game.nt.proto.GameProto.ActionType;
import com.game.nt.proto.GameProto.EndReason;
import com.game.nt.proto.GameProto.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BattleRoom 单写者（mailbox）并发正确性测试。
 *
 * <p>对应原项目 S3（跨 Netty worker 无锁共享读写）：这里用多线程并发投递命令，
 * 验证所有状态变更都在房间线程串行执行——战斗只能开始/结束一次，不会出现竞态。
 */
class BattleRoomConcurrencyTest {

    private BattleRoom room;

    @AfterEach
    void tearDown() {
        if (room != null) {
            room.close();
        }
    }

    private static BattleUnit unit(long userId, String nickname, int hp) {
        BattleUnit unit = new BattleUnit();
        unit.setUserId(userId);
        unit.setCharacterId(userId);
        unit.setNickname(nickname);
        unit.setMaxHp(hp);
        unit.setHp(hp);
        unit.setAttack(100);
        unit.setDefense(10);
        unit.getOwnedSkills().add(1);
        return unit;
    }

    /** 构建 100 HP 双方、每次攻击固定 30 伤害的房间。 */
    private void newRoom(BattleRoom.RoomOutput output) {
        BattleUnit p1 = unit(1L, "P1", 100);
        BattleUnit p2 = unit(2L, "P2", 100);
        DamageCalculator calculator = mock(DamageCalculator.class);
        when(calculator.attack(any(), any(), any(), any(Random.class)))
                .thenReturn(new DamageCalculator.Result(30, 0, false, false));
        SkillConfig skill = new SkillConfig();
        skill.setId(1);
        skill.setName("拍击");
        skill.setType("attack");
        skill.setPower(100);
        skill.setCooldown(0);
        IntFunction<SkillConfig> lookup = id -> skill;
        var economyApi = org.mockito.Mockito.mock(com.game.nt.economy.EconomyApi.class);
        room = new BattleRoom("test-battle", p1, p2, calculator, lookup, economyApi, output);
    }

    private static BattleRoom.RoomOutput spyOutput(AtomicInteger startPushes, AtomicInteger endCallbacks) {
        return new BattleRoom.RoomOutput() {
            @Override
            public void pushToUser(long userId, com.game.nt.proto.GameProto.GameMessage message) {
                if (message.getType() == MessageType.BATTLE_START) {
                    startPushes.incrementAndGet();
                }
            }

            @Override
            public void onEnded(BattleRoom r, long winnerId, long loserId, EndReason reason) {
                endCallbacks.incrementAndGet();
            }
        };
    }

    private static void awaitTrue(java.util.concurrent.Callable<Boolean> condition) {
        await().atMost(5, TimeUnit.SECONDS).until(condition);
    }

    @Test
    @DisplayName("并发准备：battle start 只广播一次（每人一条）")
    void concurrentReadysStartBattleExactlyOnce() {
        AtomicInteger startPushes = new AtomicInteger();
        AtomicInteger endCallbacks = new AtomicInteger();
        newRoom(spyOutput(startPushes, endCallbacks));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            final long uid = i % 2 == 0 ? 1L : 2L;
            pool.submit(() -> {
                try {
                    latch.await();
                    for (int j = 0; j < 20; j++) {
                        room.ready(uid);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        latch.countDown();
        pool.shutdown();
        awaitTrue(() -> room.state() == BattleRoom.State.FIGHTING);
        try {
            awaitTrue(pool::isTerminated);
        } finally {
            assertThat(errors).isEmpty();
        }
        assertThat(startPushes.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("并发操作 + 并发投降：onEnded 只回调一次，胜者唯一")
    void battleEndsExactlyOnce() {
        AtomicInteger startPushes = new AtomicInteger();
        AtomicInteger endCallbacks = new AtomicInteger();
        List<Long> winners = new CopyOnWriteArrayList<>();
        BattleRoom.RoomOutput output = new BattleRoom.RoomOutput() {
            @Override
            public void pushToUser(long userId, com.game.nt.proto.GameProto.GameMessage message) {
                if (message.getType() == MessageType.BATTLE_START) {
                    startPushes.incrementAndGet();
                }
            }

            @Override
            public void onEnded(BattleRoom r, long winnerId, long loserId, EndReason reason) {
                endCallbacks.incrementAndGet();
                winners.add(winnerId);
            }
        };
        newRoom(output);
        room.ready(1L);
        room.ready(2L);
        awaitTrue(() -> room.state() == BattleRoom.State.FIGHTING);

        // 双方狂点技能 + 多线程抢着投降：mailbox 串行化保证只结束一次
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            final long uid = i % 2 == 0 ? 1L : 2L;
            pool.submit(() -> {
                try {
                    latch.await();
                    for (int j = 0; j < 50; j++) {
                        room.action(uid, ActionType.USE_SKILL, 1);
                        room.surrender(uid);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        latch.countDown();
        pool.shutdown();
        awaitTrue(() -> room.state() == BattleRoom.State.ENDED);
        try {
            awaitTrue(pool::isTerminated);
        } finally {
            assertThat(endCallbacks.get()).isEqualTo(1);
            assertThat(winners).hasSize(1);
            assertThat(winners.get(0)).isIn(1L, 2L);
        }
    }

    @Test
    @DisplayName("非当前回合操作被拒绝（回合纪律）")
    void outOfTurnActionsRejected() {
        AtomicInteger startPushes = new AtomicInteger();
        AtomicInteger endCallbacks = new AtomicInteger();
        newRoom(spyOutput(startPushes, endCallbacks));
        room.ready(1L);
        room.ready(2L);
        awaitTrue(() -> room.state() == BattleRoom.State.FIGHTING);

        long currentActor = room.rejoinResponse().getBattleRejoinResponse().getCurrentActorUserId();
        long other = currentActor == 1L ? 2L : 1L;
        int hpBefore = (currentActor == 1L ? room.player2() : room.player1()).getHp();

        // 轮到 currentActor；other 抢操作必须被拒
        room.action(other, ActionType.USE_SKILL, 1);

        // 给 mailbox 一点时间执行（若被错误接受，HP 会变化）
        awaitTrue(() -> room.logs().size() >= 2); // 两条"已准备"日志
        int hpAfter = (currentActor == 1L ? room.player2() : room.player1()).getHp();
        assertThat(hpAfter).isEqualTo(hpBefore);
    }

    @Test
    @DisplayName("被拒动作必须回 ERROR 推送（不能静默丢弃让玩家等到超时判负）")
    void rejectedActionNotifiesActor() {
        List<com.game.nt.proto.GameProto.GameMessage> pushes = new CopyOnWriteArrayList<>();
        BattleRoom.RoomOutput output = new BattleRoom.RoomOutput() {
            @Override
            public void pushToUser(long userId, com.game.nt.proto.GameProto.GameMessage message) {
                pushes.add(message);
            }

            @Override
            public void onEnded(BattleRoom r, long winnerId, long loserId, EndReason reason) {
            }
        };
        newRoom(output);
        room.ready(1L);
        room.ready(2L);
        awaitTrue(() -> room.state() == BattleRoom.State.FIGHTING);

        long actor = room.rejoinResponse().getBattleRejoinResponse().getCurrentActorUserId();
        long other = actor == 1L ? 2L : 1L;

        // 非当前回合：拒绝并回 ERROR
        pushes.clear();
        room.action(other, ActionType.USE_SKILL, 1);
        awaitTrue(() -> pushes.stream().anyMatch(m -> m.getType() == MessageType.ERROR));

        // 未拥有的技能：拒绝并回 ERROR
        pushes.clear();
        room.action(actor, ActionType.USE_SKILL, 999);
        awaitTrue(() -> pushes.stream().anyMatch(m -> m.getType() == MessageType.ERROR
                && m.getErrorResponse().getMessage().contains("尚未学会")));
    }

    @Test
    @DisplayName("固定种子：同 battleId 先手可复现")
    void deterministicFirstActor() {
        AtomicInteger pushes = new AtomicInteger();
        AtomicInteger ends = new AtomicInteger();
        newRoom(spyOutput(pushes, ends));
        room.ready(1L);
        room.ready(2L);
        awaitTrue(() -> room.state() == BattleRoom.State.FIGHTING);
        long first = room.rejoinResponse().getBattleRejoinResponse().getCurrentActorUserId();

        // 另一个同 ID 房间（重放场景）
        if (room != null) {
            room.close();
        }
        AtomicInteger pushes2 = new AtomicInteger();
        AtomicInteger ends2 = new AtomicInteger();
        newRoom(spyOutput(pushes2, ends2));
        room.ready(1L);
        room.ready(2L);
        awaitTrue(() -> room.state() == BattleRoom.State.FIGHTING);
        long firstReplay = room.rejoinResponse().getBattleRejoinResponse().getCurrentActorUserId();

        assertThat(firstReplay).isEqualTo(first);
    }
}
