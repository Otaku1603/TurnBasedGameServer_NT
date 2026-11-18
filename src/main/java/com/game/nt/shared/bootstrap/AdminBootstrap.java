package com.game.nt.shared.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员显式引导：APP_BOOTSTRAP_ADMIN=true 时必须提供 APP_ADMIN_PASSWORD（>=12 位），
 * 无默认口令；已存在同名管理员时不覆盖。
 */
@Component
@ConditionalOnProperty(name = "app.bootstrap-admin", havingValue = "true")
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    public AdminBootstrap(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, Environment environment) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String username = environment.getProperty("app.bootstrap-admin-username", "admin").trim();
        String password = environment.getProperty("app.bootstrap-admin-password", "");
        if (username.isEmpty()) {
            throw new IllegalStateException("APP_ADMIN_USERNAME 不能为空");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "APP_BOOTSTRAP_ADMIN=true 时必须提供 APP_ADMIN_PASSWORD（至少 "
                            + MIN_PASSWORD_LENGTH + " 个字符）；系统不提供默认管理员口令");
        }

        // 多节点并发启动：用 INSERT IGNORE 让数据库唯一键裁决，避免 count-then-insert 竞态
        int inserted = jdbc.update("INSERT IGNORE INTO t_admin (username, password_hash) VALUES (?, ?)",
                username, passwordEncoder.encode(password));
        if (inserted > 0) {
            log.info("已按环境变量创建管理员: username={}（口令未记录）", username);
        } else {
            log.warn("管理员已存在，跳过引导（不覆盖现有口令）: username={}", username);
        }
    }
}
