package com.game.nt.quest.internal;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** 任务 Mapper。 */
@Mapper
public interface QuestMapper {

    /** 任务定义 + 该玩家进度（LEFT JOIN，无进度则显示 0）。 */
    @Select("""
            SELECT q.id AS questId, q.code, q.name, q.type,
                   q.target_count        AS targetCount,
                   q.reward_gold         AS rewardGold,
                   q.reward_item_id      AS rewardItemId,
                   q.reward_item_amount  AS rewardItemAmount,
                   COALESCE(p.progress, 0)       AS progress,
                   COALESCE(p.completed, 0)      AS completed,
                   COALESCE(p.reward_claimed, 0) AS rewardClaimed
              FROM t_quest q
              LEFT JOIN t_quest_progress p ON p.quest_id = q.id AND p.user_id = #{userId}
             ORDER BY q.id
            """)
    List<Map<String, Object>> listWithProgress(@Param("userId") long userId);

    /** 进度累加：不存在则按定义创建（一条语句完成 upsert）。 */
    @Insert("""
            INSERT INTO t_quest_progress (user_id, quest_id, progress)
            SELECT #{userId}, id, #{delta} FROM t_quest WHERE code = #{code}
            ON DUPLICATE KEY UPDATE
                t_quest_progress.progress = t_quest_progress.progress + #{delta},
                t_quest_progress.version = t_quest_progress.version + 1
            """)
    int addProgress(@Param("userId") long userId, @Param("code") String code, @Param("delta") int delta);

    /** 达标置完成（幂等：已完成则影响行数为 0）。 */
    @Update("""
            UPDATE t_quest_progress p
              JOIN t_quest q ON q.id = p.quest_id
               SET p.completed = 1, p.version = p.version + 1
             WHERE p.user_id = #{userId} AND q.code = #{code}
               AND p.completed = 0 AND p.progress >= q.target_count
            """)
    int markCompleted(@Param("userId") long userId, @Param("code") String code);

    /** 领取奖励：条件 UPDATE 保证"已完成且未领取"才成功（并发安全）。 */
    @Update("""
            UPDATE t_quest_progress
               SET reward_claimed = 1, version = version + 1
             WHERE user_id = #{userId} AND quest_id = #{questId}
               AND completed = 1 AND reward_claimed = 0
            """)
    int markRewardClaimed(@Param("userId") long userId, @Param("questId") int questId);

    @Select("SELECT reward_gold FROM t_quest WHERE id = #{questId}")
    Long rewardGoldOf(@Param("questId") int questId);

    @Select("SELECT reward_item_id FROM t_quest WHERE id = #{questId}")
    Integer rewardItemIdOf(@Param("questId") int questId);

    @Select("SELECT reward_item_amount FROM t_quest WHERE id = #{questId}")
    Integer rewardItemAmountOf(@Param("questId") int questId);
}
