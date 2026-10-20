package com.game.nt.admin.internal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** 后台管理 Mapper。 */
@Mapper
public interface AdminMapper {

    @Select("SELECT id, username, password_hash AS passwordHash FROM t_admin WHERE username = #{username}")
    Map<String, Object> findAdminByUsername(@Param("username") String username);

    @Select("""
            SELECT id, username, nickname, elo_rating AS eloRating, gold,
                   win_count AS winCount, total_battles AS totalBattles,
                   status, created_at AS createdAt
              FROM t_user
             ORDER BY id
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<Map<String, Object>> listUsers(@Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM t_user")
    long countUsers();

    /** 封禁/解封：status 1=正常 0=封禁。 */
    @Update("""
            UPDATE t_user SET status = #{status}, version = version + 1, updated_at = NOW()
             WHERE id = #{userId}
            """)
    int setUserStatus(@Param("userId") long userId, @Param("status") int status);

    @Select("SELECT COUNT(*) FROM t_battle_record")
    long countBattleRecords();

    @Select("""
            SELECT r.id, r.battle_id AS battleId,
                   r.player1_id AS player1Id, r.player1_nickname AS player1Nickname,
                   r.player2_id AS player2Id, r.player2_nickname AS player2Nickname,
                   r.winner_id AS winnerId, r.end_reason AS endReason,
                   r.total_rounds AS totalRounds, r.battle_duration AS duration,
                   r.is_cross_node AS crossNode, r.home_node AS homeNode,
                   r.start_time AS startTime
              FROM t_battle_record r
             ORDER BY r.id DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<Map<String, Object>> pageBattleRecords(@Param("limit") int limit, @Param("offset") int offset);
}
