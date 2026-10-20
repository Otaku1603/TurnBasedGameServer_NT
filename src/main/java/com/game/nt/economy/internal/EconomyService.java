package com.game.nt.economy.internal;

import com.game.nt.economy.EconomyApi;
import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 经济系统实现。 */
@Service
public class EconomyService implements EconomyApi {

    private final InventoryMapper inventoryMapper;

    public EconomyService(InventoryMapper inventoryMapper) {
        this.inventoryMapper = inventoryMapper;
    }

    @Override
    @Transactional
    public void grantItem(long userId, int itemId, int amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "发放数量必须为正");
        }
        inventoryMapper.grantItem(userId, itemId, amount);
    }

    @Override
    @Transactional
    public boolean tryConsumeItem(long userId, int itemId, int amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "消耗数量必须为正");
        }
        return inventoryMapper.tryConsumeItem(userId, itemId, amount) == 1;
    }

    @Override
    public int itemCount(long userId, int itemId) {
        Integer count = inventoryMapper.findCount(userId, itemId);
        return count == null ? 0 : count;
    }

    @Override
    public ItemInfo getItem(int itemId) {
        java.util.Map<String, Object> row = inventoryMapper.findItemConfig(itemId);
        if (row == null) {
            return null;
        }
        return new ItemInfo(itemId,
                String.valueOf(row.get("name")),
                String.valueOf(row.get("type")),
                row.get("effect_value") == null ? 0 : ((Number) row.get("effect_value")).intValue());
    }

    /**
     * 装备 / 卸下装饰类道具（如头像框）。
     *
     * <p>规则：仅 {@code AVATAR_FRAME} 类型可装备；必须已拥有；同一类型同时只生效一件。
     *
     * @return true=状态已变更；false=未变更（本状态已如此）
     */
    @Transactional
    public boolean equip(long userId, int itemId, boolean equip) {
        ItemInfo item = getItem(itemId);
        if (item == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "道具不存在");
        }
        if (!"AVATAR_FRAME".equals(item.type())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "该道具不可装备");
        }
        if (equip) {
            if (itemCount(userId, itemId) <= 0) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "你还没有拥有该道具");
            }
            inventoryMapper.clearEquippedByType(userId, item.type());
        }
        boolean changed = inventoryMapper.setEquipped(userId, itemId, equip) == 1;
        if (equip && !changed) {
            // setEquipped 依赖 WHERE count>0 等条件；装备失败时把刚卸下的旧装备还原
            throw new BusinessException(ErrorCode.CONFLICT, "装备失败，请重试");
        }
        return changed;
    }
}
