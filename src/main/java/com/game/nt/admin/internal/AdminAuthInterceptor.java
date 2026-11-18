package com.game.nt.admin.internal;

import com.game.nt.shared.security.JwtService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * Web 后台鉴权拦截器：校验 Cookie 中的管理员 JWT（role=ADMIN）。
 * API（/api/admin/**）走 Authorization 头；Web 页面走 HttpOnly Cookie —— 同一令牌体系、两种载体。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    public static final String COOKIE_NAME = "NT_ADMIN";

    private final JwtService jwtService;

    public AdminAuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String token = readCookie(request);
        Optional<Long> adminId = token == null ? Optional.empty() : jwtService.verifyAdminAndGetId(token);
        if (adminId.isPresent()) {
            request.setAttribute("adminId", adminId.get());
            return true;
        }
        response.sendRedirect("/admin/login");
        return false;
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
