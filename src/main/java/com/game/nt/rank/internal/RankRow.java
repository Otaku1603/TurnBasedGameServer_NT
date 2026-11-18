package com.game.nt.rank.internal;

/**
 * 排行榜行投影（模块私有）。
 *
 * <p>为什么不直接复用 {@code identity} 的 UserEntity：
 * 模块化单体下跨模块引用内部实体是被禁止的（Modulith 会校验）。
 * rank 只关心"榜单需要的字段"，因此定义自己的只读投影，符合最小依赖原则。
 */
public class RankRow {

    private Long userId;
    private String nickname;
    private Integer eloRating;
    private Long gold;
    private Integer winCount;
    private Integer totalBattles;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public Integer getEloRating() { return eloRating; }
    public void setEloRating(Integer eloRating) { this.eloRating = eloRating; }
    public Long getGold() { return gold; }
    public void setGold(Long gold) { this.gold = gold; }
    public Integer getWinCount() { return winCount; }
    public void setWinCount(Integer winCount) { this.winCount = winCount; }
    public Integer getTotalBattles() { return totalBattles; }
    public void setTotalBattles(Integer totalBattles) { this.totalBattles = totalBattles; }
}
