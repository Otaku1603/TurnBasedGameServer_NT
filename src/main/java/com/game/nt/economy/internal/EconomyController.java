package com.game.nt.economy.internal;

import com.game.nt.shared.web.CurrentUserSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 经济接口：背包查询与装饰道具装备。 */
@RestController
@RequestMapping("/api/economy")
public class EconomyController {

    private final InventoryMapper inventoryMapper;
    private final EconomyService economyService;
    private final CurrentUserSupport currentUser;

    public EconomyController(InventoryMapper inventoryMapper, EconomyService economyService,
                             CurrentUserSupport currentUser) {
        this.inventoryMapper = inventoryMapper;
        this.economyService = economyService;
        this.currentUser = currentUser;
    }

    @GetMapping("/inventory")
    public List<InventoryItemView> inventory(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return inventoryMapper.listByUser(currentUser.requireUserId(auth));
    }

    /** 装备/卸下装饰道具（如头像框）。 */
    @PostMapping("/equip")
    public Map<String, Boolean> equip(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam int itemId,
            @RequestParam(defaultValue = "true") boolean equip) {
        boolean changed = economyService.equip(currentUser.requireUserId(auth), itemId, equip);
        return Map.of("success", changed);
    }
}
