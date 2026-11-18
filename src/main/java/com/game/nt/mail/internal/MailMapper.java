package com.game.nt.mail.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 邮件 Mapper。 */
@Mapper
public interface MailMapper extends BaseMapper<MailEntity> {

    @Select("""
            SELECT COUNT(*) FROM t_mail
             WHERE receiver_id = #{userId} AND is_deleted = 0 AND is_read = 0 AND expire_at > NOW()
            """)
    int countUnread(@Param("userId") long userId);

    @Select("""
            SELECT * FROM t_mail
             WHERE receiver_id = #{userId} AND is_deleted = 0 AND expire_at > NOW()
             ORDER BY created_at DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<MailEntity> pageByReceiver(@Param("userId") long userId,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    /** 标记已读（幂等：已读则影响行数为 0）。 */
    @Update("""
            UPDATE t_mail SET is_read = 1, version = version + 1
             WHERE id = #{mailId} AND receiver_id = #{userId} AND is_read = 0
            """)
    int markRead(@Param("mailId") long mailId, @Param("userId") long userId);

    /** 软删除（校验归属，防越权删他人邮件）。 */
    @Update("""
            UPDATE t_mail SET is_deleted = 1, version = version + 1
             WHERE id = #{mailId} AND receiver_id = #{userId}
            """)
    int softDelete(@Param("mailId") long mailId, @Param("userId") long userId);

    /** 列出"存在未领取附件"的邮件（一键领取用）。 */
    @Select("""
            SELECT m.* FROM t_mail m
             WHERE m.receiver_id = #{userId}
               AND m.is_deleted = 0
               AND m.expire_at > NOW()
               AND EXISTS (SELECT 1 FROM t_mail_attachment a WHERE a.mail_id = m.id AND a.claimed = 0)
             ORDER BY m.created_at DESC
             LIMIT 100
            """)
    List<MailEntity> listClaimable(@Param("userId") long userId);

    /** 清理过期邮件（只删无未领取附件的，避免玩家资产丢失）。 */
    @Delete("""
            DELETE FROM t_mail
             WHERE expire_at < NOW()
               AND id NOT IN (SELECT DISTINCT mail_id FROM t_mail_attachment WHERE claimed = 0)
            """)
    int deleteExpired();
}
