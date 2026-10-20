package com.game.nt.gateway;

/**
 * 协议版本。服务端在每条出站消息里回填，用于客户端兼容判断。
 * 版本变更遵循「只增不改」：新增字段/消息不升版本，删除或语义变更才升。
 */
public final class ProtocolVersion {

    /** 当前协议版本（v2：枚举 0 值修复 + requestId + 强类型枚举）。 */
    public static final int CURRENT = 2;

    /** 服务端可接受的最低客户端协议版本。 */
    public static final int MIN_SUPPORTED = 2;

    private ProtocolVersion() {
    }
}
