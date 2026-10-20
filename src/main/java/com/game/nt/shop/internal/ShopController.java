package com.game.nt.shop.internal;

import com.game.nt.shared.web.CurrentUserSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 商城接口。 */
@RestController
@RequestMapping("/api/shop")
public class ShopController {

    private final ShopService shopService;
    private final CurrentUserSupport currentUser;

    public ShopController(ShopService shopService, CurrentUserSupport currentUser) {
        this.shopService = shopService;
        this.currentUser = currentUser;
    }

    @GetMapping("/items")
    public List<ShopService.ShopItemView> items() {
        return shopService.listItems();
    }

    /**
     * 购买。
     *
     * @param orderNo 客户端生成的订单号（UUID），用于防重复提交；可空
     */
    @PostMapping("/buy")
    public ShopService.BuyResult buy(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam int itemId,
            @RequestParam(defaultValue = "1") int count,
            @RequestParam(required = false) String orderNo) {
        return shopService.buy(currentUser.requireUserId(auth), itemId, count, orderNo);
    }
}
