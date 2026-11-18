package com.game.nt.shop.internal;

/** 商城商品行投影（来自 t_item）。 */
public class ShopItemRow {

    private int id;
    private String name;
    private String type;
    private int price;
    private int effectValue;

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public int getPrice() { return price; }
    public void setPrice(int price) { this.price = price; }
    public int getEffectValue() { return effectValue; }
    public void setEffectValue(int effectValue) { this.effectValue = effectValue; }
}
