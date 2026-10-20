package com.game.nt.gamedata.internal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 配置表只读查询（技能 / 道具 / 角色模板）。 */
@Mapper
public interface GamedataMapper {

    @Select("""
            SELECT id, skill_name AS skillName, skill_type AS skillType, char_type AS charType,
                   power, cooldown, mp_cost AS mpCost, target_type AS targetType
              FROM t_skill
             ORDER BY char_type, id
            """)
    List<Map<String, Object>> listSkills();

    @Select("""
            SELECT id, name, type, price, effect_value AS effectValue
              FROM t_item
             ORDER BY id
            """)
    List<Map<String, Object>> listItems();

    @Select("""
            SELECT char_type AS charType, display_name AS displayName,
                   base_hp AS baseHp, base_attack AS baseAttack, base_defense AS baseDefense,
                   base_crit_rate AS baseCritRate, base_dodge_rate AS baseDodgeRate
              FROM t_character_template
             ORDER BY id
            """)
    List<Map<String, Object>> listCharacterTemplates();
}
