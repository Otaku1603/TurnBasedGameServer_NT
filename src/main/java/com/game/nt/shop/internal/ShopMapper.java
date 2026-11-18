package com.game.nt.shop.internal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 商城 Mapper。 */
@Mapper
public interface ShopMapper {

    @Select("SELECT id, name, type, price, effect_value AS effectValue FROM t_item ORDER BY id")
    List<ShopItemRow> listItems();

    @Select("SELECT id, name, type, price, effect_value AS effectValue FROM t_item WHERE id = #{itemId}")
    ShopItemRow findItem(@Param("itemId") int itemId);
}
