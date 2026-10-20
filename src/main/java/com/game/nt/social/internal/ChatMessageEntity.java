package com.game.nt.social.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 聊天消息（t_chat_message）。 */
@TableName("t_chat_message")
public class ChatMessageEntity {

    public static final String CHANNEL_WORLD = "WORLD";
    public static final String CHANNEL_PRIVATE = "PRIVATE";
    public static final String CHANNEL_GUILD = "GUILD";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String channel;
    private Long fromId;
    private String fromName;
    private Long toId;
    private String content;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public Long getFromId() { return fromId; }
    public void setFromId(Long fromId) { this.fromId = fromId; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public Long getToId() { return toId; }
    public void setToId(Long toId) { this.toId = toId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
