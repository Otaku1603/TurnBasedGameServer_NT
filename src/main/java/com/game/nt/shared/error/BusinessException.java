package com.game.nt.shared.error;

/**
 * 业务异常：携带 {@link ErrorCode}，由全局异常处理器转换为统一响应。
 * 业务代码禁止直接抛 {@link RuntimeException}，统一走这里，保证错误码可控、可观测。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;

    public BusinessException(ErrorCode code) {
        super(code.defaultMessage());
        this.code = code;
    }

    public BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static BusinessException of(ErrorCode code) {
        return new BusinessException(code);
    }

    public static BusinessException of(ErrorCode code, String message) {
        return new BusinessException(code, message);
    }
}
