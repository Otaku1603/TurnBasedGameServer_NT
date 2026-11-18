package com.game.nt.mail.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 邮件附件 Mapper。 */
@Mapper
public interface MailAttachmentMapper extends BaseMapper<MailAttachmentEntity> {

    @Select("SELECT * FROM t_mail_attachment WHERE mail_id = #{mailId} ORDER BY id")
    List<MailAttachmentEntity> listByMail(@Param("mailId") long mailId);

    /**
     * 原子标记已领取：返回 1 表示本次抢到（可发放），0 表示已被并发领取。
     * 这是"附件不重复发放"的最后一道闸门。
     */
    @Update("UPDATE t_mail_attachment SET claimed = 1 WHERE id = #{id} AND claimed = 0")
    int markClaimed(@Param("id") long id);

    @Select("SELECT COUNT(*) FROM t_mail_attachment WHERE mail_id = #{mailId} AND claimed = 0")
    int countUnclaimed(@Param("mailId") long mailId);

    @Delete("DELETE FROM t_mail_attachment WHERE mail_id = #{mailId}")
    int deleteByMail(@Param("mailId") long mailId);
}
