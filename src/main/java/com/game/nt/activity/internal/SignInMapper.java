package com.game.nt.activity.internal;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/** 每日签到 Mapper。 */
@Mapper
public interface SignInMapper {

    @Select("SELECT COUNT(*) FROM t_sign_in WHERE user_id = #{userId} AND sign_date = #{date}")
    int countOnDate(@Param("userId") long userId, @Param("date") LocalDate date);

    @Select("SELECT sign_date FROM t_sign_in WHERE user_id = #{userId} ORDER BY sign_date DESC LIMIT 1")
    LocalDate lastSignDate(@Param("userId") long userId);

    @Select("SELECT streak FROM t_sign_in WHERE user_id = #{userId} ORDER BY sign_date DESC LIMIT 1")
    Integer lastStreak(@Param("userId") long userId);

    /**
     * 插入签到记录。唯一键 (user_id, sign_date) 保证"每天只能签一次"，
     * 并发重复提交时影响行数为 0，由调用方判定为已签到。
     */
    @Insert("""
            INSERT IGNORE INTO t_sign_in (user_id, sign_date, streak, reward_gold)
            VALUES (#{userId}, #{date}, #{streak}, #{rewardGold})
            """)
    int insert(@Param("userId") long userId, @Param("date") LocalDate date,
               @Param("streak") int streak, @Param("rewardGold") long rewardGold);
}
