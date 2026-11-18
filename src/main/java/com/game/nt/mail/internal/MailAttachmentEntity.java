package com.game.nt.mail.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 邮件附件实体（t_mail_attachment）。 */
@TableName("t_mail_attachment")
public class MailAttachmentEntity {

    public static final String TYPE_GOLD = "GOLD";
    public static final String TYPE_ITEM = "ITEM";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long mailId;
    private String type;
    private Integer itemId;
    private Integer amount;
    private Integer claimed;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMailId() { return mailId; }
    public void setMailId(Long mailId) { this.mailId = mailId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Integer getItemId() { return itemId; }
    public void setItemId(Integer itemId) { this.itemId = itemId; }
    public Integer getAmount() { return amount; }
    public void setAmount(Integer amount) { this.amount = amount; }
    public Integer getClaimed() { return claimed; }
    public void setClaimed(Integer claimed) { this.claimed = claimed; }
}
