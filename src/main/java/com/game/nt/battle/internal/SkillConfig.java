package com.game.nt.battle.internal;

/** 技能配置（战斗所需字段，来自 t_skill）。 */
public class SkillConfig {

    private int id;
    private String name;
    private String type;   // attack / heal
    private int power;     // 倍率（百分比）
    private int cooldown;

    public boolean isHeal() {
        return "heal".equalsIgnoreCase(type);
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public int getPower() { return power; }
    public void setPower(int power) { this.power = power; }
    public int getCooldown() { return cooldown; }
    public void setCooldown(int cooldown) { this.cooldown = cooldown; }
}
