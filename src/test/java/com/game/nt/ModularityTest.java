package com.game.nt;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * 模块边界校验（Spring Modulith）。
 *
 * <p>{@code verify()} 会检查：
 * <ul>
 *   <li>模块间只通过顶层 API 或领域事件通信</li>
 *   <li>不存在指向 {@code internal} 子包的跨模块引用</li>
 *   <li>没有循环依赖</li>
 * </ul>
 * 一旦违反，本测试失败 → CI 拦截。这是"架构不随时间腐化"的强制手段。
 */
class ModularityTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(NtApplication.class);

    @Test
    void moduleBoundariesAreRespected() {
        MODULES.verify();
    }

    @Test
    void moduleStructureIsDocumented() {
        // 输出模块结构，便于人工核对与写文档
        MODULES.forEach(module -> System.out.println("模块: " + module));
    }
}
