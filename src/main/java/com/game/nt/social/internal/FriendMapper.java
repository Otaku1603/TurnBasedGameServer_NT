package com.game.nt.social.internal;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 好友 Mapper。 */
@Mapper
public interface FriendMapper {

    @Select("""
            SELECT u.id AS userId, u.nickname, u.elo_rating AS eloRating, u.status
              FROM t_friend f
              JOIN t_user u ON u.id = f.friend_id
             WHERE f.user_id = #{userId} AND u.status = 1
             ORDER BY u.elo_rating DESC
            """)
    List<FriendRow> listFriends(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM t_friend WHERE user_id = #{userId} AND friend_id = #{friendId}")
    int countRelation(@Param("userId") long userId, @Param("friendId") long friendId);

    /** 双向建立好友关系（INSERT IGNORE 保证幂等）。 */
    @Insert("INSERT IGNORE INTO t_friend (user_id, friend_id) VALUES (#{userId}, #{friendId})")
    int addFriend(@Param("userId") long userId, @Param("friendId") long friendId);

    @Delete("DELETE FROM t_friend WHERE user_id = #{userId} AND friend_id = #{friendId}")
    int removeFriend(@Param("userId") long userId, @Param("friendId") long friendId);

    // ---------- 好友申请 ----------

    @Insert("""
            INSERT INTO t_friend_request (from_id, to_id, status)
            VALUES (#{fromId}, #{toId}, 'PENDING')
            ON DUPLICATE KEY UPDATE status = 'PENDING', version = version + 1
            """)
    int upsertRequest(@Param("fromId") long fromId, @Param("toId") long toId);

    @Update("""
            UPDATE t_friend_request SET status = #{status}, version = version + 1
             WHERE from_id = #{fromId} AND to_id = #{toId} AND status = 'PENDING'
            """)
    int resolveRequest(@Param("fromId") long fromId, @Param("toId") long toId, @Param("status") String status);

    @Select("""
            SELECT u.id AS userId, u.nickname, u.elo_rating AS eloRating, u.status
              FROM t_friend_request r
              JOIN t_user u ON u.id = r.from_id
             WHERE r.to_id = #{userId} AND r.status = 'PENDING'
             ORDER BY r.created_at DESC
             LIMIT 100
            """)
    List<FriendRow> listPendingRequests(@Param("userId") long userId);
}
