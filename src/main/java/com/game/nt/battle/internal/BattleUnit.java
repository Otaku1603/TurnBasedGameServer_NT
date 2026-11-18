package com.game.nt.battle.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 战斗单位（运行时状态）。
 *
 * <p><b>只在所属战斗房间线程上被读写</b>（见 {@link BattleRoom}）。
 * 因此内部使用非线程安全的 {@code HashMap} / {@code ArrayList} 是有意为之：
 * 用"单写者"保证安全，而不是到处加锁。
 */
public class BattleUnit {

    private long userId;
    private long characterId;
    private String nickname;
    private int maxHp;
    private int hp;
    private int attack;
    private int defense;
    private int critRate;
    private int dodgeRate;

    private boolean defending = false;
    private boolean ready = false;

    /** skillId -> 剩余冷却回合 */
    private final Map<Integer, Integer> cooldowns = new HashMap<>();
    /** 已学会的技能 */
    private final List<Integer> ownedSkills = new ArrayList<>();

    public boolean isAlive() {
        return hp > 0;
    }

    public boolean ownsSkill(int skillId) {
        return ownedSkills.contains(skillId);
    }

    public boolean canUseSkill(int skillId) {
        Integer remaining = cooldowns.get(skillId);
        return remaining == null || remaining <= 0;
    }

    public void putOnCooldown(int skillId, int turns) {
        if (turns > 0) {
            cooldowns.put(skillId, turns);
        }
    }

    public void reduceCooldowns() {
        cooldowns.replaceAll((skillId, remaining) -> remaining - 1);
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= 0);
    }

    public void takeDamage(int damage) {
        hp = Math.max(0, hp - damage);
    }

    public void heal(int amount) {
        hp = Math.min(maxHp, hp + amount);
    }

    // ---------- 访问器 ----------

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public long getCharacterId() { return characterId; }
    public void setCharacterId(long characterId) { this.characterId = characterId; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public int getMaxHp() { return maxHp; }
    public void setMaxHp(int maxHp) { this.maxHp = maxHp; }
    public int getHp() { return hp; }
    public void setHp(int hp) { this.hp = hp; }
    public int getAttack() { return attack; }
    public void setAttack(int attack) { this.attack = attack; }
    public int getDefense() { return defense; }
    public void setDefense(int defense) { this.defense = defense; }
    public int getCritRate() { return critRate; }
    public void setCritRate(int critRate) { this.critRate = critRate; }
    public int getDodgeRate() { return dodgeRate; }
    public void setDodgeRate(int dodgeRate) { this.dodgeRate = dodgeRate; }
    public boolean isDefending() { return defending; }
    public void setDefending(boolean defending) { this.defending = defending; }
    public boolean isReady() { return ready; }
    public void setReady(boolean ready) { this.ready = ready; }
    public Map<Integer, Integer> getCooldowns() { return cooldowns; }
    public List<Integer> getOwnedSkills() { return ownedSkills; }
}
