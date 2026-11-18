package com.game.nt.rank.internal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 排行榜查询。
 *
 * <p>与原项目的差异：原后台用 {@code findAll().size()} 把全表读进内存再计数，
 * 这里全部下推到 SQL（ORDER BY + LIMIT + COUNT），并利用索引。
 */
@Mapper
public interface RankMapper {

    @Select("""
            SELECT id AS userId, nickname, elo_rating AS eloRating,
                   win_count AS winCount, total_battles AS totalBattles
              FROM t_user
             WHERE status = 1
             ORDER BY elo_rating DESC, id ASC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<RankRow> topByElo(@Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            SELECT id AS userId, nickname, gold,
                   win_count AS winCount, total_battles AS totalBattles
              FROM t_user
             WHERE status = 1
             ORDER BY gold DESC, id ASC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<RankRow> topByGold(@Param("limit") int limit, @Param("offset") int offset);

    /** 我的名次（比多少人分数高 + 1）。 */
    @Select("""
            SELECT COUNT(*) + 1
              FROM t_user
             WHERE status = 1
               AND elo_rating > (SELECT elo_rating FROM t_user WHERE id = #{userId})
            """)
    int myEloRank(@Param("userId") long userId);
}
