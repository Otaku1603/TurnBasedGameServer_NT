package com.game.nt.shared.lua;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lua 沙箱测试。
 *
 * <p>重点验证两件事：
 * <ol>
 *   <li><b>沙箱有效</b>：脚本访问 {@code os} 等危险全局会失败（原项目这里可以 RCE）</li>
 *   <li><b>确定性</b>：相同输入必得相同输出（随机源由外部传入）</li>
 * </ol>
 */
class LuaSandboxTest {

    private LuaSandbox sandbox;

    @BeforeEach
    void setUp() {
        sandbox = new LuaSandbox("lua/damage_formulas.lua");
        sandbox.init();
    }

    @AfterEach
    void tearDown() {
        sandbox.destroy();
    }

    @Test
    @DisplayName("沙箱加载成功且可调用公式")
    void sandboxIsReady() {
        assertThat(sandbox.isReady()).isTrue();
    }

    @Test
    @DisplayName("伤害公式是确定性的：相同输入 -> 相同输出")
    void damageFormulaIsDeterministic() {
        // calc_damage(attack, defense, skill_mult, is_crit, defending, rng)
        long first = sandbox.callForLong("calc_damage", 120L, 90L, 150L, 0L, 0L, 0.5);
        long second = sandbox.callForLong("calc_damage", 120L, 90L, 150L, 0L, 0L, 0.5);

        assertThat(first).isEqualTo(second).isGreaterThan(0);
    }

    @Test
    @DisplayName("暴击应高于非暴击（同参数下）")
    void critDealsMoreDamage() {
        long normal = sandbox.callForLong("calc_damage", 120L, 90L, 150L, 0L, 0L, 0.5);
        long crit = sandbox.callForLong("calc_damage", 120L, 90L, 150L, 1L, 0L, 0.5);

        assertThat(crit).isGreaterThan(normal);
    }

    @Test
    @DisplayName("沙箱已剥离 os/io：脚本访问 os 会失败")
    void dangerousGlobalsAreStripped() {
        boolean loaded = sandbox.reload("function evil() return os.time() end");
        assertThat(loaded).isTrue();

        // os 已被置 nil，调用时必然报错（而非成功执行）
        assertThatThrownBy(() -> sandbox.callForLong("evil"))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("函数不存在时明确抛错，便于上层兜底")
    void missingFunctionThrows() {
        assertThatThrownBy(() -> sandbox.callForLong("not_exists_fn"))
                .isInstanceOf(IllegalStateException.class);
    }
}
