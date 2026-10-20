package com.game.nt.gateway.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/** 网关业务指标：在线人数。 */
@Component
public class GatewayMetrics {

    private final MeterRegistry registry;
    private final SessionManager sessionManager;

    public GatewayMetrics(MeterRegistry registry, SessionManager sessionManager) {
        this.registry = registry;
        this.sessionManager = sessionManager;
    }

    @PostConstruct
    void register() {
        Gauge.builder("nt.gateway.online.users", sessionManager, s -> s.onlineCount())
                .description("当前在线 WS 会话数")
                .register(registry);
    }
}
