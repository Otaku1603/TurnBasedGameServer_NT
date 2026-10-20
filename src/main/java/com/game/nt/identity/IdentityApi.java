package com.game.nt.identity;

import java.util.Optional;

/**
 * 身份模块对外能力（模块 API）。
 *
 * <p>其他模块只能通过本接口访问身份数据，禁止直接引用 {@code identity.internal} 下的
 * 实体与 Mapper（由 Spring Modulith 边界校验强制）。
 */
public interface IdentityApi {

    /** 账户基础信息（对外投影，不含密码哈希等敏感字段）。 */
    record AccountInfo(
            long userId,
            String username,
            String nickname,
            int eloRating,
            long gold,
            int winCount,
            int totalBattles,
            boolean banned
    ) {
    }

    /**
     * 自助注册：创建账号（初始 1000 金 / 1000 Elo）与默认战士角色。
     *
     * @throws BusinessException CONFLICT 用户名已存在；INVALID_ARGUMENT 参数不合法
     */
    AccountInfo register(String username, String rawPassword, String nickname);

    /** 按用户 ID 查询账户。 */
    Optional<AccountInfo> findByUserId(long userId);

    /** 用户名+密码认证；成功返回账户，失败返回空（不区分"用户不存在/密码错"，避免枚举）。 */
    Optional<AccountInfo> authenticate(String username, String rawPassword);

    /**
     * 原子扣减金币（单条 SQL 完成「校验余额 + 扣减」）。
     *
     * @return true=扣减成功；false=余额不足或并发冲突
     */
    boolean tryDeductGold(long userId, long amount);

    /** 原子增加金币。 */
    void addGold(long userId, long amount);
}
