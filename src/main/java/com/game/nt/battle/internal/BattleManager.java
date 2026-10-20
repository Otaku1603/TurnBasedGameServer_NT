package com.game.nt.battle.internal;

import com.game.nt.battle.BattleApi;
import com.game.nt.cluster.ClusterApi;
import com.game.nt.shared.push.ClientPushApi;
import com.game.nt.proto.GameProto.ActionType;
import com.game.nt.proto.GameProto.MessageType;
import com.game.nt.proto.GameProto.EndReason;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战斗房间管理器：battleId ⇄ 房间、userId ⇄ battleId 的索引，
 * 并作为 {@link BattleRoom.RoomOutput} 负责推送与结算回调。
 *
 * <p>注意：房间索引是<b>单机内存</b>；多实例需要把房间归属路由到具体节点（路线图 A9）。
 * 房间内部状态的并发安全由 {@link BattleRoom} 的单线程 mailbox 保证。
 */
@Service
public class BattleManager implements BattleApi, BattleRoom.RoomOutput {

    private static final Logger log = LoggerFactory.getLogger(BattleManager.class);

    private final Map<String, BattleRoom> rooms = new ConcurrentHashMap<>();
    private final Map<Long, String> userToBattle = new ConcurrentHashMap<>();
    private final Map<Long, Long> disconnectedAt = new ConcurrentHashMap<>();

    private final BattleUnitMapper unitMapper;
    private final DamageCalculator damageCalculator;
    private final ClientPushApi clientPushApi;
    private final BattleSettlementService settlementService;
    private final ClusterApi clusterApi;
    private final com.game.nt.economy.EconomyApi economyApi;

    /** battleId -> 是否跨服对局（双方在不同节点）；与房间同生命周期。 */
    private final Map<String, Boolean> crossNodeFlags = new ConcurrentHashMap<>();
    /** battleId -> 创建时刻（WAITING 房间超时回收用，开战后移除）。 */
    private final Map<String, Long> waitingSince = new ConcurrentHashMap<>();

    /** 结算失败的场次（待重试）：DB 抖动时保留现场，scheduler 周期重试。 */
    private record PendingSettle(BattleRoom room, long winnerId, long loserId, EndReason reason,
                                 boolean crossNode, long failedAtMillis) {
    }

    private final java.util.Queue<PendingSettle> pendingSettlements = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public BattleManager(BattleUnitMapper unitMapper,
                         DamageCalculator damageCalculator,
                         ClientPushApi clientPushApi,
                         BattleSettlementService settlementService,
                         ClusterApi clusterApi,
                         com.game.nt.economy.EconomyApi economyApi) {
        this.unitMapper = unitMapper;
        this.damageCalculator = damageCalculator;
        this.clientPushApi = clientPushApi;
        this.settlementService = settlementService;
        this.economyApi = economyApi;
        this.clusterApi = clusterApi;
    }

    // ==================== BattleApi ====================

    @Override
    public String createBattle(long player1Id, long player2Id, String player1Nickname, String player2Nickname) {
        BattleUnit unit1 = loadUnit(player1Id);
        BattleUnit unit2 = loadUnit(player2Id);

        String battleId = "B_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        BattleRoom room = new BattleRoom(battleId, unit1, unit2, damageCalculator,
                this::loadSkill, economyApi, this);

        rooms.put(battleId, room);
        waitingSince.put(battleId, System.currentTimeMillis());
        userToBattle.put(player1Id, battleId);
        userToBattle.put(player2Id, battleId);

        // 集群登记：战斗归属本节点；双方 user-battle 索引供跨节点断线/重连路由
        clusterApi.bindBattleHome(battleId);
        clusterApi.bindUserBattle(player1Id, battleId);
        clusterApi.bindUserBattle(player2Id, battleId);

        // 跨服判定：双方会话归属不同节点（路由缺失按本节点计）
        String node1 = clusterApi.nodeOfUser(player1Id);
        String node2 = clusterApi.nodeOfUser(player2Id);
        boolean crossNode = node1 != null && node2 != null && !node1.equals(node2);
        crossNodeFlags.put(battleId, crossNode);

        log.info("战斗创建: battleId={}, p1={}({}@{}), p2={}({}@{}), 跨服={}",
                battleId, player1Id, unit1.getNickname(), node1, player2Id, unit2.getNickname(), node2, crossNode);
        return battleId;
    }

    @Override
    public void playerReady(long userId, String battleId) {
        BattleRoom room = requireRoom(battleId, userId);
        room.ready(userId);
    }

    @Override
    public void handleAction(long userId, String battleId, ActionType actionType, int paramId) {
        BattleRoom room = requireRoom(battleId, userId);
        room.action(userId, actionType, paramId);
    }

    @Override
    public void surrender(long userId, String battleId) {
        BattleRoom room = requireRoom(battleId, userId);
        room.surrender(userId);
    }

    @Override
    public void markDisconnected(long userId) {
        if (userToBattle.containsKey(userId)) {
            disconnectedAt.put(userId, System.currentTimeMillis());
        }
    }

    @Override
    public boolean clearDisconnected(long userId) {
        disconnectedAt.remove(userId);
        return userToBattle.containsKey(userId);
    }

    @Override
    public boolean inBattle(long userId) {
        return userToBattle.containsKey(userId);
    }

    @Override
    public GameMessage rejoin(long userId) {
        BattleRoom room = roomOf(userId);
        if (room == null || room.state() == BattleRoom.State.ENDED) {
            return null;
        }
        return room.rejoinResponse();
    }

    // ==================== RoomOutput ====================

    @Override
    public void pushToUser(long userId, GameMessage message) {
        clientPushApi.sendToUser(userId, message);
    }

    @Override
    public void onEnded(BattleRoom room, long winnerId, long loserId, EndReason reason) {
        boolean crossNode = crossNodeFlags.getOrDefault(room.battleId(), false);
        try {
            settlementService.settle(room, winnerId, loserId, reason, crossNode);
        } catch (Exception ex) {
            // 结算失败（如 DB 抖动）不丢局：入待重试队列，由 scheduler 周期重试
            // （settle 幂等，重复执行安全）。房间照常清理，玩家不被卡死。
            // 注意：crossNode 必须随记录带走——remove() 会清掉 crossNodeFlags，重试时读不到了
            log.error("战斗结算异常，转入重试队列: battleId={}", room.battleId(), ex);
            pendingSettlements.add(new PendingSettle(room, winnerId, loserId, reason, crossNode,
                    System.currentTimeMillis()));
        }
        remove(room.battleId());
    }

    // ==================== 查询与维护 ====================

    public BattleRoom getRoom(String battleId) {
        return rooms.get(battleId);
    }

    public BattleRoom roomOf(long userId) {
        String battleId = userToBattle.get(userId);
        return battleId == null ? null : rooms.get(battleId);
    }

    public int activeBattleCount() {
        return rooms.size();
    }

    /**
     * 对断线超时的玩家判负（定时任务调用）。
     *
     * @return 本次判负的场次数
     */
    public int judgeTimeouts(long timeoutMillis) {
        int judged = 0;
        for (long userId : timedOutDisconnected(timeoutMillis)) {
            BattleRoom room = roomOf(userId);
            if (room != null && room.state() == BattleRoom.State.FIGHTING) {
                room.timeout(userId);
                judged++;
            } else {
                disconnectedAt.remove(userId);
            }
        }
        return judged;
    }

    /** 断线超过指定毫秒的用户（定时任务据此判负）。 */
    public java.util.List<Long> timedOutDisconnected(long timeoutMillis) {
        long now = System.currentTimeMillis();
        return disconnectedAt.entrySet().stream()
                .filter(e -> now - e.getValue() >= timeoutMillis)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 扫描全部战斗房间，对"回合内超时未行动"的当前行动者判负（在线挂机也推进对局）。
     *
     * @param limitSeconds 回合时限（scheduler 配置注入）
     * @return 触发判负的场次数
     */
    public int judgeTurnTimeouts(long limitSeconds) {
        int judged = 0;
        for (BattleRoom room : rooms.values()) {
            if (room.timeoutIfTurnExpired(limitSeconds)) {
                judged++;
            }
        }
        return judged;
    }

    /**
     * 回收超时未开战的房间（定时任务调用）。
     *
     * <p>匹配成功后双方都不点准备的 WAITING 房间原本会永久占位，
     * 导致两名玩家被 {@code inBattle} 判定卡死、无法再次匹配（线上实测缺陷）。
     * 超时后向双方推送 BATTLE_END（OPPONENT_LEFT）并完整清理索引。
     *
     * @return 本次解散的场次数
     */
    public int sweepWaitingRooms(long timeoutMillis) {
        long now = System.currentTimeMillis();
        var expired = rooms.entrySet().stream()
                .filter(e -> e.getValue().state() == BattleRoom.State.WAITING)
                .filter(e -> now - waitingSince.getOrDefault(e.getKey(), now) >= timeoutMillis)
                .map(Map.Entry::getKey)
                .toList();
        for (String battleId : expired) {
            BattleRoom room = rooms.get(battleId);
            if (room == null) {
                continue;
            }
            GameMessage notice = GameMessage.newBuilder()
                    .setVersion(2)
                    .setType(MessageType.BATTLE_END)
                    .setBattleEndResponse(com.game.nt.proto.GameProto.BattleEndResponse.newBuilder()
                            .setBattleId(battleId)
                            .setEndReason(com.game.nt.proto.GameProto.EndReason.OPPONENT_LEFT)
                            .build())
                    .build();
            pushToUser(room.player1().getUserId(), notice);
            pushToUser(room.player2().getUserId(), notice);
            remove(battleId);
            log.info("WAITING 房间超时解散: battleId={}（双方 {} / {} 未就绪）",
                    battleId, room.player1().getUserId(), room.player2().getUserId());
        }
        return expired.size();
    }

    /**
     * 重试此前失败的结算（定时任务调用）。settle 幂等，重复执行安全；
     * 超过 1 小时仍失败的场次放弃并告警（人工介入）。
     *
     * @return 本次重试成功的场次数
     */
    public int retryPendingSettlements() {
        int settled = 0;
        int size = pendingSettlements.size();
        for (int i = 0; i < size; i++) {
            PendingSettle p = pendingSettlements.poll();
            if (p == null) {
                break;
            }
            if (System.currentTimeMillis() - p.failedAtMillis() > 3600_000L) {
                log.error("结算重试超时放弃（需人工对账）: battleId={}", p.room().battleId());
                continue;
            }
            try {
                settlementService.settle(p.room(), p.winnerId(), p.loserId(), p.reason(), p.crossNode());
                settled++;
            } catch (Exception ex) {
                log.warn("结算重试仍失败，重新入队: battleId={}", p.room().battleId(), ex);
                pendingSettlements.add(p);
            }
        }
        return settled;
    }

    private void remove(String battleId) {
        crossNodeFlags.remove(battleId);
        waitingSince.remove(battleId);
        BattleRoom room = rooms.remove(battleId);
        if (room != null) {
            userToBattle.remove(room.player1().getUserId(), battleId);
            userToBattle.remove(room.player2().getUserId(), battleId);
            clusterApi.unbindUserBattle(room.player1().getUserId(), battleId);
            clusterApi.unbindUserBattle(room.player2().getUserId(), battleId);
            disconnectedAt.remove(room.player1().getUserId());
            disconnectedAt.remove(room.player2().getUserId());
            room.close();
        }
    }

    private BattleRoom requireRoom(String battleId, long userId) {
        BattleRoom room = rooms.get(battleId);
        if (room == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "战斗不存在或已结束");
        }
        if (!room.containsUser(userId)) {
            // 越权：非本场战斗的玩家不能操作
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该战斗");
        }
        return room;
    }

    private BattleUnit loadUnit(long userId) {
        BattleUnit unit = unitMapper.loadUnit(userId);
        if (unit == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "玩家没有激活角色: " + userId);
        }
        unit.setHp(unit.getMaxHp());
        unit.getOwnedSkills().addAll(unitMapper.loadSkillIds(unit.getCharacterId()));
        return unit;
    }

    private SkillConfig loadSkill(int skillId) {
        return unitMapper.loadSkill(skillId);
    }
}
