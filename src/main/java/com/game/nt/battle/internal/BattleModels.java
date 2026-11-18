package com.game.nt.battle.internal;

/** 战斗领域内的小型值对象集合。 */
final class BattleModels {

    private BattleModels() {
    }

    /** 一次技能结算的结果。 */
    record SkillOutcome(int damage, int heal, boolean dodged, boolean crit, String description) {
    }
}
