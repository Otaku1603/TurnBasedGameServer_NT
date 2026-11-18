package com.game.nt.economy;

/**
 * 经济系统对外能力（模块 API）。
 *
 * <p>所有库存变更都是**原子 SQL**，调用方无需关心并发。
 */
public interface EconomyApi {

    /** 发放道具（不存在则创建，存在则累加）。 */
    void grantItem(long userId, int itemId, int amount);

    /**
     * 消耗道具。
     *
     * @return true=扣减成功；false=数量不足
     */
    boolean tryConsumeItem(long userId, int itemId, int amount);

    /** 查询拥有数量（不存在返回 0）。 */
    int itemCount(long userId, int itemId);

    /** 道具配置快照。 */
    record ItemInfo(int id, String name, String type, int effectValue) {
    }

    /**
     * 查询道具配置。
     *
     * @return 不存在返回 null
     */
    ItemInfo getItem(int itemId);
}
