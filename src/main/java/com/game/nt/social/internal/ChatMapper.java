package com.game.nt.social.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 聊天 Mapper。 */
@Mapper
public interface ChatMapper extends BaseMapper<ChatMessageEntity> {

    @Select("""
            SELECT * FROM t_chat_message
             WHERE channel = #{channel}
             ORDER BY id DESC
             LIMIT #{limit}
            """)
    List<ChatMessageEntity> recentByChannel(@Param("channel") String channel, @Param("limit") int limit);

    @Select("""
            SELECT * FROM t_chat_message
             WHERE channel = 'PRIVATE'
               AND ((from_id = #{userA} AND to_id = #{userB}) OR (from_id = #{userB} AND to_id = #{userA}))
             ORDER BY id DESC
             LIMIT #{limit}
            """)
    List<ChatMessageEntity> recentPrivate(@Param("userA") long userA,
                                          @Param("userB") long userB,
                                          @Param("limit") int limit);
}
