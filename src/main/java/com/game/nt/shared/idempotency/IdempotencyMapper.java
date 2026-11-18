package com.game.nt.shared.idempotency;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 幂等键 Mapper。 */
@Mapper
public interface IdempotencyMapper {

    /**
     * 尝试占位。使用 INSERT IGNORE：
     * 主键冲突时不抛异常（避免把事务标记为 rollback-only），返回影响行数 0。
     */
    @Insert("INSERT IGNORE INTO t_idempotency (idem_key, status) VALUES (#{key}, 'IN_PROGRESS')")
    int tryInsert(@Param("key") String key);

    @Select("SELECT status FROM t_idempotency WHERE idem_key = #{key}")
    String findStatus(@Param("key") String key);

    @Update("UPDATE t_idempotency SET status = 'DONE', result_ref = #{resultRef} WHERE idem_key = #{key}")
    int markDone(@Param("key") String key, @Param("resultRef") String resultRef);

    @Update("DELETE FROM t_idempotency WHERE idem_key = #{key}")
    int release(@Param("key") String key);
}
