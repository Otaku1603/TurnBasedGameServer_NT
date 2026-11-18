package com.game.nt.battle.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/** 战斗业务指标：进行中对局数。 */
@Component
public class BattleMetrics {

    private final MeterRegistry registry;
    private final BattleManager battleManager;

    public BattleMetrics(MeterRegistry registry, BattleManager battleManager) {
        this.registry = registry;
        this.battleManager = battleManager;
    }

    @PostConstruct
    void register() {
        Gauge.builder("nt.battle.active", battleManager, m -> m.activeBattleCount())
                .description("进行中的战斗对局数")
                .register(registry);
    }
}
