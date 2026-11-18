package com.game.nt.battle.internal;

import com.game.nt.proto.GameProto.ActionType;
import com.game.nt.proto.GameProto.BattleEndResponse;
import com.game.nt.proto.GameProto.BattlePlayerData;
import com.game.nt.proto.GameProto.BattleStartResponse;
import com.game.nt.proto.GameProto.BattleUpdateResponse;
import com.game.nt.proto.GameProto.EndReason;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.MessageType;
import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * 战斗 Actor：单线程 mailbox 串行处理，battleId 派生固定随机种子，可复现。
 */
public class BattleRoom implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BattleRoom.class);

    public enum State { WAITING, FIGHTING, ENDED }

    /** 房间对外输出（推送 + 结算回调），由管理器注入，避免房间直接依赖网关实现。 */
    public interface RoomOutput {
        void pushToUser(long userId, GameMessage message);

        /** 战斗结束回调：做结算（幂等 + 事务）并清理房间。 */
        void onEnded(BattleRoom room, long winnerId, long loserId, EndReason reason);
    }

    private final String battleId;
    private final BattleUnit player1;
    private final BattleUnit player2;
    private final DamageCalculator damageCalculator;
    private final IntFunction<SkillConfig> skillLookup;
    private final RoomOutput output;
    private final com.game.nt.economy.EconomyApi economyApi;

    /** 单线程 mailbox：房间内所有状态变更都在这个线程上串行执行。 */
    private final ExecutorService mailbox;

    /** 以 battleId 派生种子：同一场战斗在任何节点重放结果一致 */
    private final Random rng;

    /** 房间状态：scheduler 线程会跨线程读（回合/断线超时判定），volatile 保证可见性。 */
    private volatile State state = State.WAITING;
    private long currentActorUserId;
    private int round = 1;
    private final List<String> logs = new ArrayList<>();
    private long startTimeMillis;
    private long endTimeMillis;
    /** 当前回合开始时刻（mailbox 线程写；回合超时判定用，在线挂机不行动也推进对局）。 */
    private volatile long turnStartedAtMillis;

    /**
     * 只读快照：由房间线程在每次状态变更后发布，供其他线程安全读取（断线重连）。
     * 发布的是不可变 protobuf 对象 + volatile 写，因此无需加锁即可安全读取。
     */
    private volatile BattlePlayerData snapPlayer1;
    private volatile BattlePlayerData snapPlayer2;
    private volatile long snapCurrentActorUserId;
    private volatile int snapRound = 1;

    /** 房间 mailbox 线程引用（识别"从 mailbox 线程内部关闭"场景，避免空等）。 */
    private volatile Thread mailboxThread;

    public BattleRoom(String battleId, BattleUnit player1, BattleUnit player2,
                      DamageCalculator damageCalculator,
                      IntFunction<SkillConfig> skillLookup,
                      com.game.nt.economy.EconomyApi economyApi,
                      RoomOutput output) {
        this.battleId = battleId;
        this.player1 = player1;
        this.player2 = player2;
        this.damageCalculator = damageCalculator;
        this.skillLookup = skillLookup;
        this.economyApi = economyApi;
        this.output = output;
        this.rng = new Random(battleId.hashCode() * 31L + 17L);
        this.currentActorUserId = player1.getUserId();
        this.mailbox = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "battle-room-" + battleId);
            thread.setDaemon(true);
            mailboxThread = thread;
            return thread;
        });
    }

    public String battleId() {
        return battleId;
    }

    public State state() {
        return state;
    }

    public boolean containsUser(long userId) {
        return player1.getUserId() == userId || player2.getUserId() == userId;
    }

    public long opponentOf(long userId) {
        return player1.getUserId() == userId ? player2.getUserId() : player1.getUserId();
    }

    // ==================== 命令（外部调用，内部串行执行） ====================

    public void ready(long userId) {
        submit(() -> {
            if (state != State.WAITING) {
                return;
            }
            BattleUnit unit = unitOf(userId);
            if (unit == null) {
                return;
            }
            unit.setReady(true);
            logs.add(unit.getNickname() + " 已准备");

            if (player1.isReady() && player2.isReady()) {
                startBattle();
            }
        });
    }

    public void action(long userId, ActionType actionType, int paramId) {
        submit(() -> {
            if (state != State.FIGHTING) {
                rejectAction(userId, ErrorCode.CONFLICT, "战斗尚未开始或已结束");
                return;
            }
            if (currentActorUserId != userId) {
                log.warn("非当前回合操作被拒绝: battleId={}, userId={}", battleId, userId);
                rejectAction(userId, ErrorCode.CONFLICT, "还没轮到你行动");
                return;
            }
            BattleUnit actor = unitOf(userId);
            BattleUnit target = actor == player1 ? player2 : player1;
            if (actor == null || !actor.isAlive() || !target.isAlive()) {
                rejectAction(userId, ErrorCode.CONFLICT, "当前无法行动");
                return;
            }

            try {
                switch (actionType) {
                    case USE_SKILL -> useSkill(actor, target, paramId);
                    case DEFEND -> defend(actor, target);
                    case USE_ITEM -> useItem(actor, paramId);
                    default -> rejectAction(userId, ErrorCode.INVALID_ARGUMENT, "未知操作类型");
                }
            } catch (BusinessException ex) {
                // 静默丢弃会让玩家在"点击无反应"中等到回合超时判负——必须回包
                log.warn("战斗动作被拒绝: battleId={}, userId={}, msg={}", battleId, userId, ex.getMessage());
                rejectAction(userId, ex.code(), ex.getMessage());
            }
        });
    }

    /** 动作被拒时回一条 ERROR（requestId=0，客户端按类型分发给战斗场景）。 */
    private void rejectAction(long userId, ErrorCode code, String message) {
        output.pushToUser(userId, GameMessage.newBuilder()
                .setVersion(2)
                .setType(MessageType.ERROR)
                .setErrorResponse(com.game.nt.proto.GameProto.ErrorResponse.newBuilder()
                        .setCode(com.game.nt.proto.GameProto.ErrorCode.forNumber(code.protoValue()))
                        .setMessage(message)
                        .build())
                .build());
    }

    public void surrender(long userId) {
        submit(() -> {
            if (state == State.ENDED) {
                return;
            }
            long winner = opponentOf(userId);
            endBattle(winner, EndReason.SURRENDER);
        });
    }

    /** 超时判负（挂机/断线未归）：loserUserId 被判负。 */
    public void timeout(long loserUserId) {
        submit(() -> {
            if (state == State.ENDED) {
                return;
            }
            endBattle(opponentOf(loserUserId), EndReason.TIMEOUT);
        });
    }

    /**
     * 回合内超时：FIGHTING 状态下当前行动者超过时限未行动即判负（定时任务调用）。
     * 外层读 volatile 仅做廉价预筛；"是否仍超时"在 mailbox 内复核，避免与即时行动竞态误判。
     *
     * @param limitSeconds 本轮生效的回合时限（scheduler 配置注入）
     * @return true = 已投递判负任务
     */
    public boolean timeoutIfTurnExpired(long limitSeconds) {
        if (state != State.FIGHTING) {
            return false;
        }
        long limitMs = limitSeconds * 1000L;
        if (System.currentTimeMillis() - turnStartedAtMillis < limitMs) {
            return false;
        }
        log.warn("回合超时判负: battleId={}（{}s 未行动）", battleId, limitSeconds);
        submit(() -> {
            if (state != State.FIGHTING) {
                return;
            }
            // mailbox 内复核：预筛后玩家可能已行动，回合指针已切换
            if (System.currentTimeMillis() - turnStartedAtMillis < limitMs) {
                return;
            }
            endBattle(opponentOf(currentActorUserId), EndReason.TIMEOUT);
        });
        return true;
    }

    // ==================== 房间内部逻辑（只在 mailbox 线程执行） ====================

    private void startBattle() {
        state = State.FIGHTING;
        startTimeMillis = System.currentTimeMillis();
        turnStartedAtMillis = startTimeMillis;
        // 先手：由固定种子决定，保证可复现
        currentActorUserId = rng.nextBoolean() ? player1.getUserId() : player2.getUserId();

        BattleStartResponse response = BattleStartResponse.newBuilder()
                .setBattleId(battleId)
                .setPlayer1(toPlayerData(player1))
                .setPlayer2(toPlayerData(player2))
                .setCurrentActorUserId(currentActorUserId)
                .setCurrentRound(round)
                .build();
        // 先发布开战快照，再广播 START：开战后立即重连也能拿到正确盘面
        publishSnapshots();
        broadcast(GameMessage.newBuilder()
                .setType(MessageType.BATTLE_START)
                .setBattleStartResponse(response)
                .build());
    }

    private void useSkill(BattleUnit actor, BattleUnit target, int skillId) {
        // 归属校验：不接受客户端上报的任意技能 ID（拒绝原因回包给玩家）
        if (!actor.ownsSkill(skillId)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "尚未学会该技能");
        }
        if (!actor.canUseSkill(skillId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "技能冷却中");
        }
        SkillConfig skill = skillLookup.apply(skillId);
        if (skill == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "技能不存在: " + skillId);
        }

        String description;
        int damage = 0;
        int heal = 0;
        ActionType actionType = ActionType.USE_SKILL;

        if (skill.isHeal()) {
            DamageCalculator.Result result = damageCalculator.heal(actor, skill, rng);
            heal = result.heal();
            actor.heal(heal);
            description = "%s 使用 %s，恢复 %d 点生命".formatted(actor.getNickname(), skill.getName(), heal);
        } else {
            DamageCalculator.Result result = damageCalculator.attack(actor, target, skill, rng);
            damage = result.damage();
            if (result.dodged()) {
                description = "%s 对 %s 使用 %s，被闪避".formatted(actor.getNickname(), target.getNickname(), skill.getName());
            } else {
                target.takeDamage(damage);
                description = "%s 对 %s 使用 %s，造成 %d 点伤害%s".formatted(
                        actor.getNickname(), target.getNickname(), skill.getName(), damage,
                        result.crit() ? "（暴击）" : "");
            }
        }

        actor.putOnCooldown(skillId, skill.getCooldown());
        logs.add(description);
        GameMessage update = buildUpdate(actor, target, skillId, skill.getName(), actionType, damage, heal, description);
        EndAfterAction end = advanceAfterAction(actor);
        broadcast(update);
        if (end != null) {
            endBattle(end.winnerId(), end.reason());
        }
    }

    private void defend(BattleUnit actor, BattleUnit target) {
        actor.setDefending(true);
        String description = actor.getNickname() + " 进入防御姿态";
        logs.add(description);
        GameMessage update = buildUpdate(actor, target, 0, "防御", ActionType.DEFEND, 0, 0, description);
        EndAfterAction end = advanceAfterAction(actor);
        broadcast(update);
        if (end != null) {
            endBattle(end.winnerId(), end.reason());
        }
    }

    private void useItem(BattleUnit actor, int itemId) {
        var item = economyApi.getItem(itemId);
        if (item == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "道具不存在: " + itemId);
        }
        if (!"POTION".equals(item.type())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "非药水道具不可在战斗中使用");
        }
        if (!economyApi.tryConsumeItem(actor.getUserId(), itemId, 1)) {
            throw new BusinessException(ErrorCode.CONFLICT, "道具数量不足");
        }
        int before = actor.getHp();
        actor.setHp(Math.min(actor.getMaxHp(), actor.getHp() + item.effectValue()));
        int healed = actor.getHp() - before;

        // 目标是自己（治疗类更新无对手交互）；damage 槽恒 0，heal 槽填实际回复量。
        String description = actor.getNickname() + " 使用了 " + item.name()
                + "，恢复了 " + healed + " 点生命";
        logs.add(description);
        GameMessage update = buildUpdate(actor, actor, 0, item.name(), ActionType.USE_ITEM, 0, healed, description);
        EndAfterAction end = advanceAfterAction(actor);
        broadcast(update);
        if (end != null) {
            endBattle(end.winnerId(), end.reason());
        }
    }

    /** 单局回合上限：防止"双奶互奶"等无伤害对局无限僵持（超限按剩余血量比例判负）。 */
    private static final int MAX_ROUNDS = 100;

    /** 回合内操作时限默认值（秒）：在线挂机不行动也判负，与客户端 30s 倒计时语义对齐；scheduler 可传配置覆盖。 */
    public static final long DEFAULT_TURN_TIME_LIMIT_SECONDS = 30;

    /** 一次行动推进后可能立即结束战斗：先广播 UPDATE，再按此结果发 BATTLE_END。 */
    private record EndAfterAction(long winnerId, EndReason reason) {
    }

    /**
     * 行动后的状态推进（只在 mailbox 线程执行）。
     * 先切换行动者/回合/冷却并发布快照，再由调用方广播 UPDATE。
     */
    private EndAfterAction advanceAfterAction(BattleUnit actor) {
        if (!player1.isAlive() || !player2.isAlive()) {
            publishSnapshots();
            long winner = player1.isAlive() ? player1.getUserId() : player2.getUserId();
            return new EndAfterAction(winner, EndReason.DEFEAT);
        }
        // 切换行动者 + 冷却推进（此时仍是房间线程，安全）
        currentActorUserId = opponentOf(actor.getUserId());
        round++;
        turnStartedAtMillis = System.currentTimeMillis();
        actor.reduceCooldowns();
        BattleUnit next = unitOf(currentActorUserId);
        if (next != null) {
            next.setDefending(false);
        }
        // 先发布行动后的快照，再广播 UPDATE（调用方负责），消除重连读到旧盘面的窗口
        publishSnapshots();
        if (round >= MAX_ROUNDS) {
            long winner = (long) player1.getHp() * player2.getMaxHp()
                    >= (long) player2.getHp() * player1.getMaxHp()
                    ? player1.getUserId() : player2.getUserId();
            logs.add("已达最大回合数 %d，按剩余血量判定胜者 userId=%d".formatted(MAX_ROUNDS, winner));
            return new EndAfterAction(winner, EndReason.TIMEOUT);
        }
        return null;
    }

    private void endBattle(long winnerId, EndReason reason) {
        if (state == State.ENDED) {
            return;
        }
        state = State.ENDED;
        endTimeMillis = System.currentTimeMillis();
        long loserId = opponentOf(winnerId);

        BattleEndResponse response = BattleEndResponse.newBuilder()
                .setBattleId(battleId)
                .setWinnerId(winnerId)
                .setLoserId(loserId)
                .setEndReason(reason)
                .setTotalRounds(round)
                .setPlayer1(toPlayerData(player1))
                .setPlayer2(toPlayerData(player2))
                .build();
        GameMessage endMessage = GameMessage.newBuilder()
                .setType(MessageType.BATTLE_END)
                .setBattleEndResponse(response)
                .build();
        output.pushToUser(player1.getUserId(), endMessage);
        output.pushToUser(player2.getUserId(), endMessage);

        logs.add("战斗结束，胜者 userId=" + winnerId);
        output.onEnded(this, winnerId, loserId, reason);
    }

    private GameMessage buildUpdate(BattleUnit actor, BattleUnit target, int skillId, String skillName,
                                    ActionType actionType, int damage, int heal, String description) {
        // 在 advanceAfterAction 之前构建：currentRound 是本次行动所在回合，
        // nextActorUserId 是行动方之后的接手者；快照则在推进后单独发布。
        BattleUpdateResponse response = BattleUpdateResponse.newBuilder()
                .setBattleId(battleId)
                .setCurrentRound(round)
                .setActorUserId(actor.getUserId())
                .setSkillId(skillId)
                .setSkillName(skillName)
                .setTargetUserId(target.getUserId())
                .setDamage(damage)
                .setHeal(heal)
                .setActionType(actionType)
                .setDescription(description)
                .setNextActorUserId(opponentOf(actor.getUserId()))
                .setPlayer1(toPlayerData(player1))
                .setPlayer2(toPlayerData(player2))
                .build();
        return GameMessage.newBuilder()
                .setType(MessageType.BATTLE_UPDATE)
                .setBattleUpdateResponse(response)
                .build();
    }

    private void broadcast(GameMessage message) {
        output.pushToUser(player1.getUserId(), message);
        output.pushToUser(player2.getUserId(), message);
    }

    /** 由房间线程发布只读快照。 */
    private void publishSnapshots() {
        snapPlayer1 = toPlayerData(player1);
        snapPlayer2 = toPlayerData(player2);
        snapCurrentActorUserId = currentActorUserId;
        snapRound = round;
    }

    /**
     * 断线重连：基于只读快照构建返回给客户端的盘面。
     * 原项目的重连读出了快照却从不使用（死代码），这里真正生效。
     */
    public GameMessage rejoinResponse() {
        return GameMessage.newBuilder()
                .setType(MessageType.BATTLE_REJOIN_RESPONSE)
                .setBattleRejoinResponse(com.game.nt.proto.GameProto.BattleRejoinResponse.newBuilder()
                        .setSuccess(true)
                        .setBattleId(battleId)
                        .setCurrentRound(snapRound)
                        .setCurrentActorUserId(snapCurrentActorUserId)
                        .setPlayer1(snapPlayer1 == null ? toPlayerData(player1) : snapPlayer1)
                        .setPlayer2(snapPlayer2 == null ? toPlayerData(player2) : snapPlayer2)
                        .build())
                .build();
    }

    private BattleUnit unitOf(long userId) {
        if (player1.getUserId() == userId) {
            return player1;
        }
        if (player2.getUserId() == userId) {
            return player2;
        }
        return null;
    }

    static BattlePlayerData toPlayerData(BattleUnit unit) {
        BattlePlayerData.Builder builder = BattlePlayerData.newBuilder()
                .setUserId(unit.getUserId())
                .setNickname(unit.getNickname())
                .setMaxHp(unit.getMaxHp())
                .setCurrentHp(unit.getHp())
                .setAttack(unit.getAttack())
                .setDefense(unit.getDefense())
                .setIsAlive(unit.isAlive());
        unit.getCooldowns().forEach(builder::putCooldowns);
        builder.addAllOwnedSkillIds(unit.getOwnedSkills());
        return builder.build();
    }

    // ==================== 基础设施 ====================

    private void submit(Runnable command) {
        if (mailbox.isShutdown()) {
            return;
        }
        mailbox.execute(() -> {
            try {
                command.run();
            } catch (Exception ex) {
                log.error("战斗房间命令执行异常: battleId={}", battleId, ex);
            }
        });
    }

    public List<String> logs() {
        return List.copyOf(logs);
    }

    public long startTimeMillis() {
        return startTimeMillis;
    }

    public long endTimeMillis() {
        return endTimeMillis;
    }

    public BattleUnit player1() {
        return player1;
    }

    public BattleUnit player2() {
        return player2;
    }

    public int round() {
        return round;
    }

    @Override
    public void close() {
        mailbox.shutdown();
        // 从房间 mailbox 线程内关闭（endBattle → BattleManager.remove → close）时，
        // 当前任务未返回线程就不会终止，awaitTermination 必然空等满 2s；
        // shutdown 后排队任务会随当前任务返回自然排空，直接跳过等待。
        if (Thread.currentThread() == mailboxThread) {
            return;
        }
        try {
            mailbox.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
