package com.game.nt.risk.internal;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 风控事件持久化。 */
@Mapper
public interface RiskMapper {

    @Insert("""
            INSERT INTO t_risk_event (user_id, type, severity, detail)
            VALUES (#{userId}, #{type}, #{severity}, #{detail})
            """)
    int insert(@Param("userId") Long userId, @Param("type") String type,
               @Param("severity") String severity, @Param("detail") String detail);

    @Select("""
            SELECT id, user_id, type, severity, detail, status, created_at
              FROM t_risk_event
             WHERE (#{status} IS NULL OR status = #{status})
             ORDER BY created_at DESC
             LIMIT #{size} OFFSET #{offset}
            """)
    List<RiskEventRow> page(@Param("status") String status,
                            @Param("offset") int offset, @Param("size") int size);

    @Select("""
            SELECT COUNT(*) FROM t_risk_event WHERE (#{status} IS NULL OR status = #{status})
            """)
    long count(@Param("status") String status);

    @Update("""
            UPDATE t_risk_event SET status = #{status} WHERE id = #{id}
            """)
    int updateStatus(@Param("id") long id, @Param("status") String status);
}
