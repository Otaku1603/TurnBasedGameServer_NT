package com.game.nt.battle.internal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 战斗单位加载：只取战斗需要的列（避免拉整行）。 */
@Mapper
public interface BattleUnitMapper {

    @Select("""
            SELECT c.id AS characterId, c.user_id AS userId, u.nickname,
                   c.max_hp AS maxHp, c.attack, c.defense,
                   c.crit_rate AS critRate, c.dodge_rate AS dodgeRate
              FROM t_character c
              JOIN t_user u ON u.id = c.user_id
             WHERE c.user_id = #{userId} AND c.is_active = 1
             LIMIT 1
            """)
    BattleUnit loadUnit(@Param("userId") long userId);

    @Select("SELECT skill_id FROM t_character_skill WHERE character_id = #{characterId}")
    List<Integer> loadSkillIds(@Param("characterId") long characterId);

    @Select("""
            SELECT id, skill_name AS name, skill_type AS type, power, cooldown
              FROM t_skill
             WHERE id = #{skillId}
            """)
    SkillConfig loadSkill(@Param("skillId") int skillId);
}
