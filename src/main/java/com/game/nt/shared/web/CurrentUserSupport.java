package com.game.nt.shared.web;

import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import com.game.nt.shared.security.JwtService;
import org.springframework.stereotype.Component;

/**
 * HTTP 侧"从请求解析当前用户"。所有需要登录的接口统一走这里，避免各处重复解析与漏校验。
 */
@Component
public class CurrentUserSupport {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public CurrentUserSupport(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /** 要求已认证，否则抛 401。 */
    public long requireUserId(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少访问令牌");
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return jwtService.verifyAndGetUserId(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "令牌无效或已过期"));
    }

    /** 要求管理员令牌（role=ADMIN），玩家令牌会被拒绝。 */
    public long requireAdminId(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少访问令牌");
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return jwtService.verifyAdminAndGetId(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "需要管理员权限"));
    }
}
