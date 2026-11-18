package com.game.nt.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 启动配置审计：strict 模式下 JWT/DB/NODE_ID/seed-demo 不达标即拒绝启动。 */
@Component
public class StartupConfigAudit implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupConfigAudit.class);

    /** 与 application.yml 中 JWT 密钥兜底值保持一致。 */
    private static final String DEV_JWT_SECRET = "dev-only-insecure-secret-please-change-me-0123456789abcdef";

    private final Environment environment;

    public StartupConfigAudit(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> issues = new ArrayList<>();

        String jwtSecret = environment.getProperty("app.jwt.secret");
        if (jwtSecret == null || jwtSecret.isBlank() || DEV_JWT_SECRET.equals(jwtSecret)) {
            issues.add("JWT 密钥仍为开发默认值 → 请通过 JWT_SECRET 注入 >=32 字节随机密钥");
        }

        String dbPassword = environment.getProperty("spring.datasource.password");
        if (dbPassword == null || dbPassword.isBlank()) {
            issues.add("数据库口令为空 → 请通过 DB_PASSWORD / SPRING_DATASOURCE_PASSWORD 注入");
        }

        String nodeId = environment.getProperty("app.cluster.node-id");
        if (nodeId == null || nodeId.isBlank()) {
            issues.add("未显式配置 NODE_ID → 默认按进程派生，节点重启会更换 ID，"
                    + "跨节点路由/战斗归属在 TTL 过期前会短暂失效");
        }

        // 部署环境不得播种演示账号（测试玩家弱口令）。
        // 属性未显式设为 false 即视为风险（默认 true，本地开发不受影响）。
        String seedDemo = environment.getProperty("app.seed-demo");
        if (!"false".equalsIgnoreCase(seedDemo)) {
            issues.add("演示数据播种未关闭（app.seed-demo != false）→ 部署环境会存在测试玩家弱口令，"
                    + "请通过 APP_SEED_DEMO=false 显式关闭");
        }

        // 管理员引导：不允许默认口令；打开开关但口令过短/缺失视为配置错误。
        boolean bootstrapAdmin = environment.getProperty("app.bootstrap-admin", Boolean.class, false);
        if (bootstrapAdmin) {
            String adminPassword = environment.getProperty("app.bootstrap-admin-password", "");
            if (adminPassword == null || adminPassword.length() < 12) {
                issues.add("APP_BOOTSTRAP_ADMIN=true 但 APP_ADMIN_PASSWORD 缺失或短于 12 字符 → "
                        + "系统不提供默认管理员口令");
            }
        }

        if (issues.isEmpty()) {
            log.info("启动配置审计通过：JWT/DB/NODE_ID 均已显式配置");
            return;
        }

        boolean strict = environment.getProperty("app.strict-config", Boolean.class, false);
        String report = String.join("\n  - ", issues);
        if (strict) {
            throw new IllegalStateException("启动配置审计未通过（app.strict-config=true，拒绝启动）:\n  - " + report);
        }
        log.warn("启动配置审计发现问题（当前为非严格模式，仅告警）:\n  - {}", report);
    }
}
