package com.game.nt.shared.error;

/**
 * 统一错误码。与 {@code game.proto} 中的 {@code ErrorCode} 枚举一一对应，
 * 服务端内部使用本枚举，出协议时映射为 proto 值。
 */
public enum ErrorCode {

    OK(1, "成功"),
    UNAUTHORIZED(2, "未登录或令牌无效"),
    FORBIDDEN(3, "无权访问该资源"),
    INVALID_ARGUMENT(4, "参数非法"),
    NOT_FOUND(5, "资源不存在"),
    CONFLICT(6, "状态冲突"),
    RATE_LIMITED(7, "请求过于频繁"),
    INTERNAL(8, "服务器内部错误");

    private final int protoValue;
    private final String defaultMessage;

    ErrorCode(int protoValue, String defaultMessage) {
        this.protoValue = protoValue;
        this.defaultMessage = defaultMessage;
    }

    public int protoValue() {
        return protoValue;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
