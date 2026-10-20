package com.game.nt.social.internal;

/** 好友列表投影（含基础展示信息与在线状态由上层补充）。 */
public class FriendRow {

    private Long userId;
    private String nickname;
    private Integer eloRating;
    private Integer status;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public Integer getEloRating() { return eloRating; }
    public void setEloRating(Integer eloRating) { this.eloRating = eloRating; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
