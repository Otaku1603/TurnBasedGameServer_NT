package com.game.nt.shared.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 本地开发演示账号播种（仅 local profile + app.seed-demo=true）。
 * 参考数据由 Flyway V7 提供；管理员由 AdminBootstrap 显式创建。
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "app.seed-demo", havingValue = "true", matchIfMissing = true)
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
            requireReferenceData();
            seedDemoPlayers();
        } finally {
            redis.delete(SEED_LOCK_KEY);
        }
    }

    /** 参考数据必须由 Flyway V7 提供；缺失说明迁移链未正确执行。 */
    private void requireReferenceData() {
        Integer templates = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_character_template", Integer.class);
        Integer skills = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_skill", Integer.class);
        Integer items = jdbc.queryForObject("SELECT COUNT(*) FROM t_item", Integer.class);
        if (templates == null || templates == 0 || skills == null || skills == 0
                || items == null || items == 0) {
            throw new IllegalStateException(
                    "参考数据缺失：Flyway V7__seed_reference_data.sql 未生效（templates="
                            + templates + ", skills=" + skills + ", items=" + items + "）");
        }
    }

    private void seedDemoPlayers() {
        seedPlayer("player1", "player1pass", "战士一号", "warrior");
        seedPlayer("player2", "player2pass", "法师二号", "mage");
        // 压测用玩家池：player3..player14 全战士（保证任意配对都有伤害来源，
        // 避免法师互奶的对局僵持；player2 保留法师用于技能覆盖）
        for (int i = 3; i <= 14; i++) {
            seedPlayer("player" + i, "player" + i + "pass", "压测玩家" + i, "warrior");
        }
        log.info("本地演示账号检查完成（参考数据由 Flyway V7 提供）");
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

        log.info("已创建演示玩家 {}/{}（职业 {}）", username, rawPassword, charType);
    }
}
