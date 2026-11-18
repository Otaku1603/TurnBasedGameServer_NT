package com.game.nt.match.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/** 匹配业务指标：匹配队列长度。 */
@Component
public class MatchMetrics {

    private final MeterRegistry registry;
    private final MatchService matchService;

    public MatchMetrics(MeterRegistry registry, MatchService matchService) {
        this.registry = registry;
        this.matchService = matchService;
    }

    @PostConstruct
    void register() {
        Gauge.builder("nt.match.queue.size", matchService, s -> s.queueSize())
                .description("匹配队列中的玩家数")
                .register(registry);
    }
}
