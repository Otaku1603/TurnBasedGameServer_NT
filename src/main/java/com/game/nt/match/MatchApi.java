package com.game.nt.match;

/**
 * 匹配模块对外能力（模块 API）。
 * 网关的 MATCH_REQUEST / MATCH_CANCEL 消息处理器通过本接口驱动匹配。
 */
public interface MatchApi {

    /** 加入匹配队列（重复加入是幂等的）。 */
    void join(long userId);

    /** 取消匹配。 */
    boolean cancel(long userId);

    /** 当前队列长度（观测用）。 */
    int queueSize();
}
