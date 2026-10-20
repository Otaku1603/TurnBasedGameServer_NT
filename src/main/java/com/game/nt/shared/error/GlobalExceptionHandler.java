package com.game.nt.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理：统一错误响应结构，避免把堆栈/内部信息回吐给客户端。
 * 原项目直接 {@code message = e.getMessage()} 回显，属信息泄露，这里统一收敛。
 *
 * <p>注意：{@code basePackages} 必须限定在业务包内，否则会**连 Actuator 端点一起接管**，
 * 把本该是 503 的健康检查变成 500。
 */
@RestControllerAdvice(basePackages = "com.game.nt")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 统一响应体 */
    public record ApiError(int code, String error, String message, String path, Instant timestamp) {
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex, HttpServletRequest req) {
        log.warn("业务异常: code={}, msg={}, path={}", ex.code(), ex.getMessage(), req.getRequestURI());
        return ResponseEntity.status(toHttpStatus(ex.code()))
                .body(new ApiError(ex.code().protoValue(), ex.code().name(), ex.getMessage(),
                        req.getRequestURI(), Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("参数校验失败");
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCode.INVALID_ARGUMENT.protoValue(), ErrorCode.INVALID_ARGUMENT.name(),
                        msg, req.getRequestURI(), Instant.now()));
    }

    /**
     * 请求体不可解析（非法 JSON / 非 UTF-8 字节）→ 400。
     *
     * <p>真机验证发现：客户端用非 UTF-8 编码发送中文时，Jackson 抛 StreamReadException
     * 会被 {@link #handleOther} 兜成 500，误导排查。malformed 输入属于客户端问题，应是 400。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        log.warn("请求体不可解析: path={}, reason={}", req.getRequestURI(), ex.getMessage());
        return badRequest("请求体格式错误（需为合法 UTF-8 JSON）", req);
    }

    /** 路径/查询参数类型不匹配（如 /api/guild/abc 期望数字）→ 400，而不是 500。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        log.warn("参数类型不匹配: path={}, name={}", req.getRequestURI(), ex.getName());
        return badRequest("参数类型不匹配: " + ex.getName(), req);
    }

    /** 缺少必填查询参数 → 400。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest req) {
        log.warn("缺少请求参数: path={}, name={}", req.getRequestURI(), ex.getParameterName());
        return badRequest("缺少必填参数: " + ex.getParameterName(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex, HttpServletRequest req) {
        // 内部细节只进日志，不返回给客户端
        log.error("未处理异常: path={}", req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(ErrorCode.INTERNAL.protoValue(), ErrorCode.INTERNAL.name(),
                        ErrorCode.INTERNAL.defaultMessage(), req.getRequestURI(), Instant.now()));
    }

    private static ResponseEntity<ApiError> badRequest(String message, HttpServletRequest req) {
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCode.INVALID_ARGUMENT.protoValue(), ErrorCode.INVALID_ARGUMENT.name(),
                        message, req.getRequestURI(), Instant.now()));
    }

    private HttpStatus toHttpStatus(ErrorCode code) {
        return switch (code) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
