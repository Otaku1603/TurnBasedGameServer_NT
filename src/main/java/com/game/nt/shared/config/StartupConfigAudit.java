package com.game.nt.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动配置审计（S5 + C3）。
 *
 * <p>背景：`application.yml` 为本地开发提供了兜底默认值（开发用 JWT 密钥、空 DB 口令），
 * 而节点 ID 默认按进程名派生。一旦在集群部署时忘记注入环境变量，
 * 就会出现"用不安全默认值启动"或"节点重启即换 ID 导致跨节点路由短暂失效"——
 * 这两种问题在运行时很难发现，必须在启动阶段暴露。
 *
 * <p>策略：
 * <ul>
 *   <li>默认：逐条 <b>WARN</b>，不阻断本地裸跑；</li>
 *   <li>{@code app.strict-config=true}（集群 compose 已设置）：任一项不达标<b>直接拒绝启动</b>。</li>
 * </ul>
 */
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
