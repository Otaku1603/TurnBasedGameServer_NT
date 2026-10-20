package com.game.nt.battle.internal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 战报查询（玩家侧战绩历史 / 单局战报）。 */
@Mapper
public interface BattleRecordMapper {

    @Select("""
            SELECT battle_id AS battleId,
                   player1_id AS player1Id, player1_nickname AS player1Nickname,
                   player1_elo_before AS player1EloBefore, player1_elo_after AS player1EloAfter,
                   player2_id AS player2Id, player2_nickname AS player2Nickname,
                   player2_elo_before AS player2EloBefore, player2_elo_after AS player2EloAfter,
                   winner_id AS winnerId, end_reason AS endReason,
                   total_rounds AS totalRounds, battle_duration AS duration,
                   is_cross_node AS crossNode, end_time AS endTime
              FROM t_battle_record
             WHERE player1_id = #{userId} OR player2_id = #{userId}
             ORDER BY id DESC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> listByUser(@Param("userId") long userId, @Param("limit") int limit);

    @Select("""
            SELECT battle_id AS battleId,
                   player1_id AS player1Id, player1_nickname AS player1Nickname,
                   player1_final_hp AS player1FinalHp,
                   player1_elo_before AS player1EloBefore, player1_elo_after AS player1EloAfter,
                   player2_id AS player2Id, player2_nickname AS player2Nickname,
                   player2_final_hp AS player2FinalHp,
                   player2_elo_before AS player2EloBefore, player2_elo_after AS player2EloAfter,
                   winner_id AS winnerId, end_reason AS endReason,
                   total_rounds AS totalRounds, battle_duration AS duration,
                   home_node AS homeNode, is_cross_node AS crossNode,
                   start_time AS startTime, end_time AS endTime,
                   battle_log_json AS battleLog
              FROM t_battle_record
             WHERE battle_id = #{battleId}
            """)
    Map<String, Object> findByBattleId(@Param("battleId") String battleId);
}
