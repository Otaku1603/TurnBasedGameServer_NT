package com.game.nt.battle;

import com.game.nt.proto.GameProto.ActionType;
import com.game.nt.proto.GameProto.GameMessage;

/**
 * 战斗模块对外能力（模块 API）。
 *
 * <p>调用方（匹配、网关 handler）只通过本接口驱动战斗，
 * 不直接接触房间内部状态 —— 所有操作都会被投递到对应房间线程串行执行。
 */
public interface BattleApi {

    /**
     * 创建一场战斗（匹配成功后调用）。
     *
     * @return battleId
     */
    String createBattle(long player1Id, long player2Id, String player1Nickname, String player2Nickname);

    /** 玩家准备就绪；双方都准备后自动开战。 */
    void playerReady(long userId, String battleId);

    /** 战斗操作：使用技能 / 防御 / 使用道具。 */
    void handleAction(long userId, String battleId, ActionType actionType, int paramId);

    /** 投降。 */
    void surrender(long userId, String battleId);

    /** 玩家断线标记（用于超时判负）。 */
    void markDisconnected(long userId);

    /** 玩家重连，清除断线标记；返回是否仍在同一场战斗中。 */
    boolean clearDisconnected(long userId);

    /** 是否在战斗中。 */
    boolean inBattle(long userId);

    /**
     * 断线重连：返回当前战斗盘面消息（BATTLE_REJOIN_RESPONSE）。
     *
     * @return 不在进行中的战斗中则返回 null
     */
    GameMessage rejoin(long userId);
}
