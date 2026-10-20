package com.game.nt.quest;

/**
 * 任务/成就模块对外能力（模块 API）。
 *
 * <p>其他玩法模块（战斗、签到、社交…）通过本接口上报进度，实现"行为驱动任务"。
 * 例如：战斗结束后 {@code questApi.addProgress(userId, "WIN_10_BATTLES", 1)}。
 */
public interface QuestApi {

    /**
     * 累加任务进度；达到目标自动置为已完成。
     *
     * @param code  任务业务码（t_quest.code）
     * @param delta 增量
     */
    void addProgress(long userId, String code, int delta);
}
