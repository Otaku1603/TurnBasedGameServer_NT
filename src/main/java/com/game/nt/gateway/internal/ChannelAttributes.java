package com.game.nt.gateway.internal;

import io.netty.util.AttributeKey;

/**
 * Channel 属性键。
 *
 * <p>核心安全约定：<b>登录后把 userId 写入 Channel 属性，业务消息一律从这里取身份，
 * 绝不信任客户端消息体里上报的 userId</b>（原项目最大的越权漏洞 S1）。
 */
public final class ChannelAttributes {

    private ChannelAttributes() {
    }

    /** 已认证的用户 ID（未登录为 null） */
    public static final AttributeKey<Long> USER_ID = AttributeKey.valueOf("nt.userId");

    /** 用户名（日志用） */
    public static final AttributeKey<String> USERNAME = AttributeKey.valueOf("nt.username");

    /** 链路追踪 ID */
    public static final AttributeKey<String> TRACE_ID = AttributeKey.valueOf("nt.traceId");

    /** 真实客户端 IP（替代原项目反射修改 remoteAddress 的黑魔法） */
    public static final AttributeKey<String> REAL_IP = AttributeKey.valueOf("nt.realIp");
}
