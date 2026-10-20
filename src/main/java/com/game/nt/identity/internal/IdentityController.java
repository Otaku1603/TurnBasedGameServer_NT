package com.game.nt.identity.internal;

import com.game.nt.identity.IdentityApi;
import com.game.nt.risk.RiskApi;
import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import com.game.nt.shared.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证 HTTP 接口（无状态，供客户端获取 JWT）。
 * 登录失败统一返回 401 且不区分原因，避免用户名枚举。
 */
@RestController
@RequestMapping("/api/auth")
public class IdentityController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(long userId, String nickname, String token) {
    }

    private final IdentityApi identityApi;
    private final JwtService jwtService;
    private final RiskApi riskApi;

    public IdentityController(IdentityApi identityApi, JwtService jwtService, RiskApi riskApi) {
        this.identityApi = identityApi;
        this.jwtService = jwtService;
        this.riskApi = riskApi;
    }

    @PostMapping("/register")
    public LoginResponse register(@Valid @RequestBody RegisterRequest request) {
        IdentityApi.AccountInfo account = identityApi.register(
                request.username(), request.password(), request.nickname());
        String token = jwtService.issue(account.userId(), account.username());
        return new LoginResponse(account.userId(), account.nickname(), token);
    }

    public record RegisterRequest(@jakarta.validation.constraints.NotBlank String username,
                                  @jakarta.validation.constraints.NotBlank String password,
                                  String nickname) {
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String ip = clientIp(http);
        try {
            IdentityApi.AccountInfo account = identityApi
                    .authenticate(request.username(), request.password())
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
            String token = jwtService.issue(account.userId(), account.username());
            return new LoginResponse(account.userId(), account.nickname(), token);
        } catch (BusinessException ex) {
            // 登录失败进入风控：限流 + 爆破审计（401 响应保持不区分原因）
            riskApi.onLoginFailure(request.username(), ip);
            throw ex;
        }
    }

    private static String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim()
                : http.getRemoteAddr();
    }
}
