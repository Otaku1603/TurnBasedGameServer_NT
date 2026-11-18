package com.game.nt.shared.web;

import jakarta.servlet.http.HttpServletRequest;

/** 提取客户端真实 IP（经反代时取 X-Forwarded-For 首段）。限流与审计口径统一。 */
public final class ClientIps {

    private ClientIps() {
    }

    public static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
    }
}
