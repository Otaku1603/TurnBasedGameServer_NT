package com.game.nt.shop.internal;

import com.game.nt.economy.EconomyApi;
import com.game.nt.identity.IdentityApi;
import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import com.game.nt.shared.idempotency.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商城服务 —— 补全经济闭环：结算赚金币 → 商城花金币 → 背包得道具。
 *
 * <p><b>原子性</b>：扣费用 identity 的单条原子 UPDATE（余额不足直接失败），
 * 发货用 economy 的原子 upsert；两者在同一事务，扣了钱没发货是不可能的。
 *
 * <p><b>幂等</b>：购买本身允许重复（买两次得两份），但客户端会生成 orderNo，
 * 同一订单号重复提交会被幂等键拦截 —— 防的是"网络重试导致连点双扣"这类资损。
 */
@Service
public class ShopService {

    private static final Logger log = LoggerFactory.getLogger(ShopService.class);
    private static final int MAX_COUNT_PER_ORDER = 99;

    public record ShopItemView(int id, String name, String type, int price, int effectValue) {
    }

    public record BuyResult(int itemId, String itemName, int count, long totalCost, long goldLeft) {
    }

    private final ShopMapper shopMapper;
    private final IdentityApi identityApi;
    private final EconomyApi economyApi;
    private final IdempotencyService idempotencyService;

    public ShopService(ShopMapper shopMapper, IdentityApi identityApi,
                       EconomyApi economyApi, IdempotencyService idempotencyService) {
        this.shopMapper = shopMapper;
        this.identityApi = identityApi;
        this.economyApi = economyApi;
        this.idempotencyService = idempotencyService;
    }

    public List<ShopItemView> listItems() {
        return shopMapper.listItems().stream()
                .map(item -> new ShopItemView(item.getId(), item.getName(), item.getType(),
                        item.getPrice(), item.getEffectValue()))
                .toList();
    }

    @Transactional
    public BuyResult buy(long userId, int itemId, int count, String orderNo) {
        if (count <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "购买数量必须为正");
        }
        if (count > MAX_COUNT_PER_ORDER) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "单次最多购买 " + MAX_COUNT_PER_ORDER + " 个");
        }
        ShopItemRow item = shopMapper.findItem(itemId);
        if (item == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        long totalCost = (long) item.getPrice() * count;

        if (orderNo != null && !orderNo.isBlank()) {
            if (!idempotencyService.tryAcquire("shop:buy:" + userId + ":" + orderNo.strip())) {
                throw new BusinessException(ErrorCode.CONFLICT, "请勿重复提交订单");
            }
        }

        // 原子扣费：余额不足返回 false，不会出现负余额
        if (!identityApi.tryDeductGold(userId, totalCost)) {
            throw new BusinessException(ErrorCode.CONFLICT, "金币不足（需要 " + totalCost + "）");
        }
        economyApi.grantItem(userId, itemId, count);

        long goldLeft = identityApi.findByUserId(userId)
                .map(IdentityApi.AccountInfo::gold)
                .orElse(0L);

        log.info("购买成功: userId={}, item={}x{}, 花费={}, 余款={}",
                userId, itemId, count, totalCost, goldLeft);
        return new BuyResult(itemId, item.getName(), count, totalCost, goldLeft);
    }
}
