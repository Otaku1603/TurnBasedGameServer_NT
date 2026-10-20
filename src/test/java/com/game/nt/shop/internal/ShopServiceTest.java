package com.game.nt.shop.internal;

import com.game.nt.economy.EconomyApi;
import com.game.nt.identity.IdentityApi;
import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.idempotency.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商城服务单元测试：扣费-发货同事务、幂等键拦截、余额不足不发货。
 *
 * <p>真正的并发安全由原子 SQL 保证（{@code WHERE gold >= ?}），
 * 这里的 mock 测试验证的是服务层的调用次序与守卫逻辑。
 */
class ShopServiceTest {

    private ShopMapper shopMapper;
    private IdentityApi identityApi;
    private EconomyApi economyApi;
    private IdempotencyService idempotencyService;
    private ShopService shopService;

    @BeforeEach
    void setUp() {
        shopMapper = mock(ShopMapper.class);
        identityApi = mock(IdentityApi.class);
        economyApi = mock(EconomyApi.class);
        idempotencyService = mock(IdempotencyService.class);
        shopService = new ShopService(shopMapper, identityApi, economyApi, idempotencyService);
    }

    private ShopItemRow item(int id, String name, int price) {
        ShopItemRow row = new ShopItemRow();
        row.setId(id);
        row.setName(name);
        row.setType("POTION");
        row.setPrice(price);
        row.setEffectValue(200);
        return row;
    }

    @Test
    @DisplayName("正常购买：扣费与发货按正确参数执行")
    void buyDeductsAndGrants() {
        when(shopMapper.findItem(1)).thenReturn(item(1, "药水", 50));
        when(idempotencyService.tryAcquire(anyString())).thenReturn(true);
        when(identityApi.tryDeductGold(7L, 100L)).thenReturn(true);
        when(identityApi.findByUserId(7L)).thenReturn(Optional.of(new IdentityApi.AccountInfo(7L, "p", "P1", 1000, 900L, 0, 0, false)));

        ShopService.BuyResult result = shopService.buy(7L, 1, 2, "order-1");

        assertThat(result.totalCost()).isEqualTo(100L);
        assertThat(result.goldLeft()).isEqualTo(900L);
        verify(identityApi).tryDeductGold(7L, 100L);
        verify(economyApi).grantItem(7L, 1, 2);
    }

    @Test
    @DisplayName("同一订单号重复提交被幂等键拦截，不扣款不发货")
    void duplicateOrderRejected() {
        when(shopMapper.findItem(1)).thenReturn(item(1, "药水", 50));
        when(idempotencyService.tryAcquire("shop:buy:7:order-1")).thenReturn(false);

        assertThatThrownBy(() -> shopService.buy(7L, 1, 2, "order-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请勿重复提交订单");

        verify(identityApi, never()).tryDeductGold(anyLong(), anyLong());
        verify(economyApi, never()).grantItem(anyLong(), eq(1), eq(2));
    }

    @Test
    @DisplayName("余额不足：抛出冲突异常且不发货")
    void insufficientGoldNoGrant() {
        when(shopMapper.findItem(1)).thenReturn(item(1, "药水", 50));
        when(idempotencyService.tryAcquire(anyString())).thenReturn(true);
        when(identityApi.tryDeductGold(7L, 2500L)).thenReturn(false);

        assertThatThrownBy(() -> shopService.buy(7L, 1, 50, "order-2"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("金币不足");

        verify(economyApi, never()).grantItem(anyLong(), eq(1), eq(50));
    }

    @Test
    @DisplayName("超过单笔上限被拒绝")
    void countCapEnforced() {
        assertThatThrownBy(() -> shopService.buy(7L, 1, 100, "order-3"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多购买");

        verify(shopMapper, never()).findItem(1);
    }

    @Test
    @DisplayName("商品不存在")
    void itemNotFound() {
        when(shopMapper.findItem(99)).thenReturn(null);

        assertThatThrownBy(() -> shopService.buy(7L, 99, 1, "order-4"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("商品不存在");
    }

    @Test
    @DisplayName("幂等键使用 userId + orderNo 组合，不同玩家订单号不冲突")
    void idempotencyKeyIsUserScoped() {
        when(shopMapper.findItem(1)).thenReturn(item(1, "药水", 50));
        when(idempotencyService.tryAcquire(anyString())).thenReturn(true);
        when(identityApi.tryDeductGold(anyLong(), anyLong())).thenReturn(true);
        when(identityApi.findByUserId(anyLong()))
                .thenReturn(Optional.of(new IdentityApi.AccountInfo(7L, "p", "P1", 1000, 100L, 0, 0, false)));

        shopService.buy(7L, 1, 1, "same-order");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(idempotencyService).tryAcquire(key.capture());
        assertThat(key.getValue()).isEqualTo("shop:buy:7:same-order");
    }
}
