package com.game.nt.economy.internal;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 背包 Mapper：所有变更都是原子 SQL，杜绝并发下的数量错乱。 */
@Mapper
public interface InventoryMapper {

    /** 发放：不存在则插入，存在则累加（一条语句完成，天然并发安全）。 */
    @Insert("""
            INSERT INTO t_user_inventory (user_id, item_id, `count`)
            VALUES (#{userId}, #{itemId}, #{amount})
            ON DUPLICATE KEY UPDATE `count` = `count` + #{amount}, version = version + 1
            """)
    int grantItem(@Param("userId") long userId, @Param("itemId") int itemId, @Param("amount") int amount);

    /** 消耗：带数量校验，返回影响行数（0 = 数量不足）。 */
    @Update("""
            UPDATE t_user_inventory
               SET `count` = `count` - #{amount}, version = version + 1
             WHERE user_id = #{userId} AND item_id = #{itemId} AND `count` >= #{amount}
            """)
    int tryConsumeItem(@Param("userId") long userId, @Param("itemId") int itemId, @Param("amount") int amount);

    @Select("SELECT `count` FROM t_user_inventory WHERE user_id = #{userId} AND item_id = #{itemId}")
    Integer findCount(@Param("userId") long userId, @Param("itemId") int itemId);

    @Select("""
            SELECT i.item_id AS itemId, it.name AS name, it.type AS type,
                   i.`count` AS count, i.is_equipped AS equipped
              FROM t_user_inventory i
              JOIN t_item it ON it.id = i.item_id
             WHERE i.user_id = #{userId} AND i.`count` > 0
             ORDER BY i.item_id
            """)
    java.util.List<InventoryItemView> listByUser(@Param("userId") long userId);

    /** 装备/卸下（仅拥有时可操作，返回影响行数）。 */
    @Update("""
            UPDATE t_user_inventory
               SET is_equipped = #{equipped}, version = version + 1
             WHERE user_id = #{userId} AND item_id = #{itemId} AND `count` > 0
            """)
    int setEquipped(@Param("userId") long userId, @Param("itemId") int itemId,
                    @Param("equipped") boolean equipped);

    /** 同一类型的装饰道具同时只装备一件：先清空该类型已装备项。 */
    @Update("""
            UPDATE t_user_inventory i
              JOIN t_item it ON it.id = i.item_id
               SET i.is_equipped = 0, i.version = i.version + 1
             WHERE i.user_id = #{userId} AND it.type = #{type} AND i.is_equipped = 1
            """)
    int clearEquippedByType(@Param("userId") long userId, @Param("type") String type);

    /**
     * 道具配置（战斗用药水判定与回血量）。
     *
     * @return [name, type, effectValue]；不存在返回 null
     */
    @Select("SELECT name, type, effect_value FROM t_item WHERE id = #{itemId}")
    java.util.Map<String, Object> findItemConfig(@org.apache.ibatis.annotations.Param("itemId") int itemId);
}
