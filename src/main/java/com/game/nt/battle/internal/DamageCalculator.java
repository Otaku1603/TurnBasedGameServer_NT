package com.game.nt.battle.internal;

import com.game.nt.shared.lua.LuaSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * 伤害计算：优先走 Lua 公式，失败自动降级到 Java 兜底。
 *
 * <p><b>确定性</b>：随机数由本类的 {@link Random}（以 battleId 为种子）产生并作为参数传给 Lua，
 * 脚本自身不产生随机 —— 只要种子与操作序列相同，整场战斗可完整复现（回放 / 对账 / 反作弊）。
 *
 * <p><b>防作弊</b>：伤害永远在服务端计算，客户端只上传"用哪个技能"。
 */
@Component
public class DamageCalculator {

    private static final Logger log = LoggerFactory.getLogger(DamageCalculator.class);

    private final LuaSandbox luaSandbox;

    public DamageCalculator(LuaSandbox luaSandbox) {
        this.luaSandbox = luaSandbox;
    }

    public record Result(int damage, int heal, boolean crit, boolean dodged) {
    }

    /** 攻击技能结算。 */
    public Result attack(BattleUnit actor, BattleUnit target, SkillConfig skill, Random rng) {
        // 1) 闪避判定（闪避率上限 95%，避免无敌）
        double dodgeRoll = rng.nextDouble();
        if (rollDodge(target.getDodgeRate(), dodgeRoll)) {
            return new Result(0, 0, false, true);
        }

        // 2) 暴击判定
        boolean crit = rng.nextDouble() * 100.0 < actor.getCritRate();
        double damageRoll = rng.nextDouble();

        int damage = computeWithLua(actor, target, skill, crit, damageRoll);
        return new Result(damage, 0, crit, false);
    }

    /** 治疗技能结算。 */
    public Result heal(BattleUnit actor, SkillConfig skill, Random rng) {
        double roll = rng.nextDouble();
        int heal;
        try {
            if (luaSandbox.isReady()) {
                heal = (int) luaSandbox.callForLong("calc_heal",
                        (long) actor.getAttack(), (long) skill.getPower(), roll);
            } else {
                heal = fallbackHeal(actor, skill, roll);
            }
        } catch (Exception ex) {
            log.warn("Lua 治疗公式失败，使用 Java 兜底: {}", ex.getMessage());
            heal = fallbackHeal(actor, skill, roll);
        }
        return new Result(0, heal, false, false);
    }

    private int computeWithLua(BattleUnit actor, BattleUnit target, SkillConfig skill,
                               boolean crit, double damageRoll) {
        try {
            if (luaSandbox.isReady()) {
                return (int) luaSandbox.callForLong("calc_damage",
                        (long) actor.getAttack(),
                        (long) target.getDefense(),
                        (long) skill.getPower(),
                        crit ? 1L : 0L,
                        target.isDefending() ? 1L : 0L,
                        damageRoll);
            }
        } catch (Exception ex) {
            log.warn("Lua 伤害公式失败，使用 Java 兜底: {}", ex.getMessage());
        }
        return fallbackDamage(actor, target, skill, crit, damageRoll);
    }

    private boolean rollDodge(int dodgeRate, double roll) {
        try {
            if (luaSandbox.isReady()) {
                return luaSandbox.callForLong("calc_dodge", (long) dodgeRate, roll) == 1L;
            }
        } catch (Exception ex) {
            log.debug("Lua 闪避公式失败，使用 Java 兜底: {}", ex.getMessage());
        }
        double rate = Math.min(Math.max(dodgeRate, 0), 95) / 100.0;
        return roll < rate;
    }

    // ---------- Java 兜底公式（与 Lua 保持一致，Lua 不可用时保证同语义）----------

    private static int fallbackDamage(BattleUnit actor, BattleUnit target, SkillConfig skill,
                                      boolean crit, double roll) {
        double mitigation = target.getDefense() / (target.getDefense() + 200.0);
        double base = actor.getAttack() * (skill.getPower() / 100.0) * (1.0 - mitigation);
        if (crit) {
            base *= 1.5;
        }
        if (target.isDefending()) {
            base *= 0.5;
        }
        base *= 0.9 + roll * 0.2;
        return Math.max(1, (int) Math.floor(base));
    }

    private static int fallbackHeal(BattleUnit actor, SkillConfig skill, double roll) {
        double base = actor.getAttack() * (skill.getPower() / 100.0) * (0.95 + roll * 0.1);
        return Math.max(1, (int) Math.floor(base));
    }
}
