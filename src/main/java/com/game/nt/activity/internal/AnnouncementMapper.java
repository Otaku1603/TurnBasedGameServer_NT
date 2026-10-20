package com.game.nt.activity.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 公告 Mapper。 */
@Mapper
public interface AnnouncementMapper extends BaseMapper<AnnouncementEntity> {

    @Select("""
            SELECT * FROM t_announcement
             WHERE active = 1
               AND (start_at IS NULL OR start_at <= NOW())
               AND (end_at IS NULL OR end_at >= NOW())
             ORDER BY id DESC
             LIMIT #{limit}
            """)
    List<AnnouncementEntity> listActive(@Param("limit") int limit);
}
