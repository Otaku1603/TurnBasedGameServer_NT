package com.game.nt.shared.rating;

/**
 * 标准 Elo 计算（零和）。
 *
 * <p>修正原项目的"伪 ELO"：原实现是"赢 +25 / 输 -15"，每局净 +10，
 * 会导致全服分数持续通胀、段位逐步失真。标准 Elo 是零和的：
 * 赢家获得的分数恰好等于输家失去的分数，长期看全服总分守恒。
 *
 * <p>公式：{@code Ea = 1 / (1 + 10^((Rb - Ra) / 400))}，{@code Δ = K × (1 - Ea)}。
 */
public final class EloCalculator {

    /** 默认 K 值：越大分数变动越快（新手期常用 32，高水平用 16）。 */
    public static final int DEFAULT_K = 32;

    private EloCalculator() {
    }

    /** 返回 [赢家新分, 输家新分]，保证零和且分数不为负。 */
    public static int[] apply(int winnerRating, int loserRating, int k) {
        int safeK = k <= 0 ? DEFAULT_K : k;
        double expectedWinner = 1.0 / (1.0 + Math.pow(10.0, (loserRating - winnerRating) / 400.0));
        int delta = (int) Math.round(safeK * (1.0 - expectedWinner));
        if (delta < 1) {
            delta = 1; // 高分段赢低分段也至少 +1，避免完全不动
        }
        int newWinner = winnerRating + delta;
        int newLoser = Math.max(0, loserRating - delta);
        return new int[]{newWinner, newLoser};
    }
}
