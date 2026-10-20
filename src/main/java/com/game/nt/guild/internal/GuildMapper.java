package com.game.nt.guild.internal;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** 公会 Mapper。 */
@Mapper
public interface GuildMapper {

    @Select("SELECT COUNT(*) FROM t_guild WHERE name = #{name}")
    int countByName(@Param("name") String name);

    @Insert("""
            INSERT INTO t_guild (name, leader_id, level, exp, notice, member_count, max_members)
            VALUES (#{name}, #{leaderId}, 1, 0, #{notice}, 1, #{maxMembers})
            """)
    int insertGuild(@Param("name") String name, @Param("leaderId") long leaderId,
                    @Param("notice") String notice, @Param("maxMembers") int maxMembers);

    @Select("SELECT id FROM t_guild WHERE name = #{name}")
    Long findIdByName(@Param("name") String name);

    @Select("SELECT guild_id FROM t_guild_member WHERE user_id = #{userId}")
    Long findGuildIdByUser(@Param("userId") long userId);

    @Select("SELECT id, name, leader_id AS leaderId, level, exp, notice, member_count AS memberCount, max_members AS maxMembers FROM t_guild WHERE id = #{guildId}")
    Map<String, Object> findGuild(@Param("guildId") long guildId);

    @Select("SELECT name FROM t_guild WHERE id = #{guildId}")
    String findName(@Param("guildId") long guildId);

    /** 成员列表（含昵称与 ELO，直接给前端展示）。 */
    @Select("""
            SELECT m.user_id AS userId, u.nickname, u.elo_rating AS eloRating, m.position
              FROM t_guild_member m
              JOIN t_user u ON u.id = m.user_id
             WHERE m.guild_id = #{guildId}
             ORDER BY FIELD(m.position, 'LEADER', 'OFFICER', 'MEMBER'), m.joined_at
            """)
    List<Map<String, Object>> listMembers(@Param("guildId") long guildId);

    @Insert("""
            INSERT IGNORE INTO t_guild_member (guild_id, user_id, position)
            VALUES (#{guildId}, #{userId}, #{position})
            """)
    int addMember(@Param("guildId") long guildId, @Param("userId") long userId,
                  @Param("position") String position);

    @Delete("DELETE FROM t_guild_member WHERE guild_id = #{guildId} AND user_id = #{userId}")
    int removeMember(@Param("guildId") long guildId, @Param("userId") long userId);

    @Select("SELECT position FROM t_guild_member WHERE guild_id = #{guildId} AND user_id = #{userId}")
    String findPosition(@Param("guildId") long guildId, @Param("userId") long userId);

    @Update("""
            UPDATE t_guild SET member_count = member_count + #{delta}, version = version + 1
             WHERE id = #{guildId} AND member_count + #{delta} >= 0
            """)
    int bumpMemberCount(@Param("guildId") long guildId, @Param("delta") int delta);

    @Delete("DELETE FROM t_guild WHERE id = #{guildId}")
    int deleteGuild(@Param("guildId") long guildId);

    /** 解散时清理全部成员（否则成员 findGuildIdByUser 仍指向已删除公会）。 */
    @Delete("DELETE FROM t_guild_member WHERE guild_id = #{guildId}")
    int deleteMembers(@Param("guildId") long guildId);

    @Delete("DELETE FROM t_guild_application WHERE guild_id = #{guildId}")
    int deleteApplications(@Param("guildId") long guildId);

    @Delete("DELETE FROM t_guild_message WHERE guild_id = #{guildId}")
    int deleteMessages(@Param("guildId") long guildId);

    // ==================== 列表 / 搜索 ====================

    @Select("""
            SELECT id, name, leader_id AS leaderId, level, exp, notice,
                   member_count AS memberCount, max_members AS maxMembers
              FROM t_guild
             WHERE name LIKE CONCAT('%', #{keyword}, '%')
             ORDER BY level DESC, member_count DESC, id
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<Map<String, Object>> listGuilds(@Param("keyword") String keyword,
                                         @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM t_guild WHERE name LIKE CONCAT('%', #{keyword}, '%')")
    int countGuilds(@Param("keyword") String keyword);

    // ==================== 入会申请 ====================

    /** 提交/重提申请（重复申请重置为 PENDING）。 */
    @Insert("""
            INSERT INTO t_guild_application (guild_id, user_id, status)
            VALUES (#{guildId}, #{userId}, 'PENDING')
            ON DUPLICATE KEY UPDATE status = 'PENDING', version = version + 1
            """)
    int upsertApplication(@Param("guildId") long guildId, @Param("userId") long userId);

    @Select("""
            SELECT a.user_id AS userId, u.nickname, u.elo_rating AS eloRating, a.created_at AS appliedAt
              FROM t_guild_application a
              JOIN t_user u ON u.id = a.user_id
             WHERE a.guild_id = #{guildId} AND a.status = 'PENDING'
             ORDER BY a.created_at
            """)
    List<Map<String, Object>> listApplications(@Param("guildId") long guildId);

    /** 审批：仅 PENDING 可流转，返回影响行数（0 = 不存在或已处理）。 */
    @Update("""
            UPDATE t_guild_application
               SET status = #{status}, version = version + 1
             WHERE guild_id = #{guildId} AND user_id = #{userId} AND status = 'PENDING'
            """)
    int resolveApplication(@Param("guildId") long guildId, @Param("userId") long userId,
                           @Param("status") String status);

    // ==================== 成员管理 ====================

    @Update("UPDATE t_guild_member SET position = #{position}, version = version + 1 WHERE guild_id = #{guildId} AND user_id = #{userId}")
    int updatePosition(@Param("guildId") long guildId, @Param("userId") long userId,
                       @Param("position") String position);

    @Select("SELECT leader_id FROM t_guild WHERE id = #{guildId}")
    Long findLeaderId(@Param("guildId") long guildId);

    @Update("UPDATE t_guild SET leader_id = #{leaderId}, version = version + 1 WHERE id = #{guildId}")
    int updateLeader(@Param("guildId") long guildId, @Param("leaderId") long leaderId);

    @Select("SELECT user_id FROM t_guild_member WHERE guild_id = #{guildId}")
    List<Long> listMemberIds(@Param("guildId") long guildId);

    // ==================== 公告 ====================

    @Update("UPDATE t_guild SET notice = #{notice}, version = version + 1 WHERE id = #{guildId}")
    int updateNotice(@Param("guildId") long guildId, @Param("notice") String notice);

    // ==================== 公会聊天 ====================

    /**
     * 写入公会聊天。
     *
     * <p>用 {@code Map} 承载参数以便回填自增主键（{@code useGeneratedKeys} 无法直接写回多个标量参数），
     * 调用方插入后可从 {@code params.get("id")} 取到真实消息 ID。
     */
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    @Insert("""
            INSERT INTO t_guild_message (guild_id, from_id, from_name, content)
            VALUES (#{guildId}, #{fromId}, #{fromName}, #{content})
            """)
    int insertMessage(java.util.Map<String, Object> params);

    @Select("""
            SELECT id, from_id AS fromId, from_name AS fromName, content, created_at AS createdAt
              FROM t_guild_message
             WHERE guild_id = #{guildId}
             ORDER BY id DESC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> recentMessages(@Param("guildId") long guildId, @Param("limit") int limit);
}
