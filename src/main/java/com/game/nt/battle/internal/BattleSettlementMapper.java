package com.game.nt.battle.internal;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 战斗结算持久化。 */
@Mapper
public interface BattleSettlementMapper {

    /** 结算后更新玩家：ELO / 胜场 / 总场次 / 金币，一次原子 UPDATE。 */
    @Update("""
            UPDATE t_user
               SET elo_rating = #{elo},
                   win_count = win_count + #{winInc},
                   total_battles = total_battles + 1,
                   gold = gold + #{gold},
                   version = version + 1,
                   updated_at = NOW()
             WHERE id = #{userId}
            """)
    int updateAfterBattle(@Param("userId") long userId,
                          @Param("elo") int elo,
                          @Param("winInc") int winInc,
                          @Param("gold") long gold);

    /**
     * 写入战报。{@code battle_id} 上有唯一索引 —— 这是结算幂等的<b>第二道防线</b>
     * （第一道是幂等键）：即使幂等键被绕过，重复插入也会失败。
     */
    @Insert("""
            INSERT INTO t_battle_record
                (battle_id, player1_id, player1_nickname, player1_final_hp,
                 player1_elo_before, player1_elo_after,
                 player2_id, player2_nickname, player2_final_hp,
                 player2_elo_before, player2_elo_after,
                 winner_id, end_reason, total_rounds, battle_duration, battle_log_json,
                 start_time, end_time, home_node, is_cross_node)
            VALUES
                (#{battleId}, #{p1Id}, #{p1Nickname}, #{p1Hp},
                 #{p1EloBefore}, #{p1EloAfter},
                 #{p2Id}, #{p2Nickname}, #{p2Hp},
                 #{p2EloBefore}, #{p2EloAfter},
                 #{winnerId}, #{endReason}, #{totalRounds}, #{duration}, #{logJson},
                 #{startTime}, #{endTime}, #{homeNode}, #{crossNode})
            """)
    int insertRecord(@Param("battleId") String battleId,
                     @Param("p1Id") long p1Id, @Param("p1Nickname") String p1Nickname,
                     @Param("p1Hp") int p1Hp,
                     @Param("p1EloBefore") int p1EloBefore, @Param("p1EloAfter") int p1EloAfter,
                     @Param("p2Id") long p2Id, @Param("p2Nickname") String p2Nickname,
                     @Param("p2Hp") int p2Hp,
                     @Param("p2EloBefore") int p2EloBefore, @Param("p2EloAfter") int p2EloAfter,
                     @Param("winnerId") long winnerId, @Param("endReason") String endReason,
                     @Param("totalRounds") int totalRounds, @Param("duration") int duration,
                     @Param("logJson") String logJson,
                     @Param("startTime") java.time.LocalDateTime startTime,
                     @Param("endTime") java.time.LocalDateTime endTime,
                     @Param("homeNode") String homeNode,
                     @Param("crossNode") boolean crossNode);
}
