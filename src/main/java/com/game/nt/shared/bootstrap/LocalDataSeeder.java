package com.game.nt.shared.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 本地开发种子数据（仅 local profile）。
 *
 * <p>为什么要用代码播种而不是 SQL 脚本：
 * <ul>
 *   <li>密码哈希在运行时用 BCrypt 生成，避免把哈希明文写进仓库（原项目 admin 与玩家共用同一哈希 = 弱口令泄露）</li>
 *   <li>幂等：已存在则跳过，可重复启动</li>
 *   <li>多实例安全：Redis SETNX 抢播种锁，集群下多节点同时启动只有一家播种
 *       （两个节点并发 INSERT 会撞唯一键，ApplicationRunner 失败会拖垮启动）</li>
 * </ul>
 */
@Component
@Profile("local")
public class LocalDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalDataSeeder.class);

    private static final String SEED_LOCK_KEY = "lock:seed";
    private static final Duration SEED_LOCK_TTL = Duration.ofSeconds(120);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    public LocalDataSeeder(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                           org.springframework.data.redis.core.StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 多节点并发启动：只有抢到锁的一家播种；没抢到的等下一次重启或信任对方完成
        Boolean locked = redis.opsForValue().setIfAbsent(SEED_LOCK_KEY, "1", SEED_LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            log.info("其他节点正在播种，本节点跳过");
            return;
        }
        try {
            doSeed(args);
        } finally {
            redis.delete(SEED_LOCK_KEY);
        }
    }

    private void doSeed(ApplicationArguments args) {
        seedCharacterTemplates();
        seedSkills();
        seedItems();
        seedAdmin();
        seedQuests();
        seedPlayer("player1", "player1pass", "战士一号", "warrior");
        seedPlayer("player2", "player2pass", "法师二号", "mage");
        // 压测用玩家池：player3..player14 全战士（保证任意配对都有伤害来源，
        // 避免法师互奶的对局僵持；player2 保留法师用于技能覆盖）
        for (int i = 3; i <= 14; i++) {
            seedPlayer("player" + i, "player" + i + "pass", "压测玩家" + i, "warrior");
        }
        log.info("本地种子数据检查完成");
    }


    private void seedCharacterTemplates() {
        upsertTemplate("warrior", "战士", 1200, 120, 90, 8, 5);
        upsertTemplate("mage", "法师", 900, 160, 60, 10, 6);
        upsertTemplate("rogue", "游侠", 1000, 135, 70, 15, 12);
    }

    private void upsertTemplate(String type, String name, int hp, int atk, int def, int crit, int dodge) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_character_template WHERE char_type = ?", Integer.class, type);
        if (count != null && count > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO t_character_template
                    (char_type, display_name, base_hp, base_attack, base_defense, base_crit_rate, base_dodge_rate)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, type, name, hp, atk, def, crit, dodge);
    }

    private void seedSkills() {
        upsertSkill("普通攻击", "attack", "all", 100, 0, "ENEMY");
        upsertSkill("重击", "attack", "warrior", 150, 2, "ENEMY");
        upsertSkill("治疗术", "heal", "mage", 120, 3, "SELF");
        upsertSkill("疾风斩", "attack", "rogue", 130, 1, "ENEMY");
        // 补授：为所有已存在角色补上全职业通用技能（幂等），历史账号也能获得普攻
        jdbc.update("""
                INSERT IGNORE INTO t_character_skill (character_id, skill_id)
                SELECT c.id, s.id FROM t_character c JOIN t_skill s ON s.char_type = 'all'
                """);
    }

    private void upsertSkill(String name, String type, String charType, int power, int cooldown, String targetType) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_skill WHERE skill_name = ?", Integer.class, name);
        if (count != null && count > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO t_skill (skill_name, skill_type, char_type, power, cooldown, mp_cost, target_type)
                VALUES (?, ?, ?, ?, ?, 0, ?)
                """, name, type, charType, power, cooldown, targetType);
    }

    private void seedItems() {
        upsertItem("初级治疗药水", "POTION", 50, 200);
        upsertItem("头像框·青铜", "AVATAR_FRAME", 200, 0);
    }

    private void upsertItem(String name, String type, int price, int effect) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_item WHERE name = ?", Integer.class, name);
        if (count != null && count > 0) {
            return;
        }
        jdbc.update("INSERT INTO t_item (name, type, price, effect_value) VALUES (?, ?, ?, ?)",
                name, type, price, effect);
    }

    private void seedQuests() {
        upsertQuest("DAILY_SIGN_IN", "每日签到", "DAILY", 1, 50);
        upsertQuest("WIN_BATTLE", "首场胜利", "ACHIEVEMENT", 1, 200);
        upsertQuest("WIN_10_BATTLES", "十胜成就", "ACHIEVEMENT", 10, 1000);
    }

    private void upsertQuest(String code, String name, String type, int targetCount, long rewardGold) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_quest WHERE code = ?", Integer.class, code);
        if (count != null && count > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO t_quest (code, name, type, target_count, reward_gold, reward_item_amount)
                VALUES (?, ?, ?, ?, ?, 0)
                """, code, name, type, targetCount, rewardGold);
    }

    private void seedAdmin() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_admin WHERE username = ?", Integer.class, "admin");
        if (count != null && count > 0) {
            return;
        }
        // 管理员密码与玩家密码各自独立哈希，杜绝"用玩家密码登录后台"
        jdbc.update("INSERT INTO t_admin (username, password_hash) VALUES (?, ?)",
                "admin", passwordEncoder.encode("admin123"));
        log.warn("已创建默认管理员 admin/admin123 —— 仅本地开发用，生产环境务必删除或改密");
    }

    private void seedPlayer(String username, String rawPassword, String nickname, String charType) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE username = ?", Integer.class, username);
        if (count != null && count > 0) {
            // 职业不符（历史种子）则删建，保证种子定义与库内一致
            String current = jdbc.queryForObject(
                    "SELECT c.char_type FROM t_character c JOIN t_user u ON u.id = c.user_id WHERE u.username = ?",
                    String.class, username);
            if (charType.equals(current)) {
                return;
            }
            Long oldId = jdbc.queryForObject("SELECT id FROM t_user WHERE username = ?", Long.class, username);
            jdbc.update("DELETE FROM t_character_skill WHERE character_id IN (SELECT id FROM t_character WHERE user_id = ?)", oldId);
            jdbc.update("DELETE FROM t_character WHERE user_id = ?", oldId);
            jdbc.update("DELETE FROM t_user WHERE id = ?", oldId);
            log.info("种子玩家职业不符，已重建: username={}, {} -> {}", username, current, charType);
        }
        jdbc.update("""
                INSERT INTO t_user (username, nickname, password_hash, elo_rating, gold, win_count, total_battles, status)
                VALUES (?, ?, ?, 1000, 1000, 0, 0, 1)
                """, username, nickname, passwordEncoder.encode(rawPassword));

        Long userId = jdbc.queryForObject(
                "SELECT id FROM t_user WHERE username = ?", Long.class, username);

        var template = jdbc.queryForMap(
                "SELECT * FROM t_character_template WHERE char_type = ?", charType);

        jdbc.update("""
                INSERT INTO t_character
                    (user_id, char_name, char_type, max_hp, attack, defense, crit_rate, dodge_rate, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
                """,
                userId, nickname, charType,
                template.get("base_hp"), template.get("base_attack"), template.get("base_defense"),
                template.get("base_crit_rate"), template.get("base_dodge_rate"));

        Long characterId = jdbc.queryForObject(
                "SELECT id FROM t_character WHERE user_id = ? AND is_active = 1", Long.class, userId);

        jdbc.queryForList("SELECT id FROM t_skill WHERE char_type = ? OR char_type = 'all'", Integer.class, charType)
                .forEach(skillId -> jdbc.update(
                        "INSERT IGNORE INTO t_character_skill (character_id, skill_id) VALUES (?, ?)",
                        characterId, skillId));

        log.info("已创建测试玩家 {}/{}（职业 {}）", username, rawPassword, charType);
    }
}
