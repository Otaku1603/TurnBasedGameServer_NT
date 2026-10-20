package com.game.nt.shared.rating;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Elo 计算测试。
 *
 * <p>重点验证原项目"伪 ELO"的两处问题已被修正：
 * <ol>
 *   <li><b>零和</b>：赢家所得 = 输家所失（原实现赢 +25 / 输 -15，每局净 +10，全服通胀）</li>
 *   <li><b>分差影响</b>：低分赢高分涨得多（原实现恒定）</li>
 * </ol>
 */
class EloCalculatorTest {

    @Test
    @DisplayName("零和：赢家所得恰好等于输家所失")
    void isZeroSum() {
        int[] result = EloCalculator.apply(1000, 1000, EloCalculator.DEFAULT_K);

        int winnerGain = result[0] - 1000;
        int loserLoss = 1000 - result[1];

        assertThat(winnerGain).isPositive();
        assertThat(winnerGain).isEqualTo(loserLoss);
    }

    @Test
    @DisplayName("同等分数对局：赢家约 +K/2")
    void evenMatchGainsHalfK() {
        int[] result = EloCalculator.apply(1200, 1200, 32);
        assertThat(result[0] - 1200).isEqualTo(16);
    }

    @Test
    @DisplayName("以弱胜强：低分方涨分明显多于高分方")
    void upsetGainsMoreThanExpectedWin() {
        int upsetGain = EloCalculator.apply(800, 1600, 32)[0] - 800;
        int expectedWinGain = EloCalculator.apply(1600, 800, 32)[0] - 1600;

        assertThat(upsetGain).isGreaterThan(expectedWinGain);
    }

    @Test
    @DisplayName("分数不会跌破 0")
    void neverGoesNegative() {
        int[] result = EloCalculator.apply(1600, 5, 32);
        assertThat(result[1]).isGreaterThanOrEqualTo(0);
    }
}
