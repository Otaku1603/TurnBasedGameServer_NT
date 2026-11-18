package com.game.nt.identity.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 用户 Mapper。 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    @Select("SELECT * FROM t_user WHERE username = #{username} LIMIT 1")
    UserEntity findByUsername(@Param("username") String username);

    /**
     * 原子扣减金币：把「读-判断-写」压缩为单条 SQL，消除并发双花。
     * 返回影响行数；0 表示余额不足或并发冲突（调用方据此判定失败）。
     */
    @Update("""
            UPDATE t_user
               SET gold = gold - #{amount},
                   version = version + 1,
                   updated_at = NOW()
             WHERE id = #{userId}
               AND gold >= #{amount}
            """)
    int tryDeductGold(@Param("userId") long userId, @Param("amount") long amount);

    /** 原子加金币。 */
    @Update("""
            UPDATE t_user
               SET gold = gold + #{amount},
                   version = version + 1,
                   updated_at = NOW()
             WHERE id = #{userId}
            """)
    int addGold(@Param("userId") long userId, @Param("amount") long amount);
}
