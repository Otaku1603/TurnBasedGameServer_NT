package com.game.nt.admin.internal;

import com.game.nt.risk.RiskApi;
import com.game.nt.shared.security.JwtService;
import com.game.nt.shared.web.ClientIps;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * Web 管理后台（Thymeleaf，沿用原项目 UI 风格：Bootstrap 5.3 + 深色侧栏扁平布局）。
 *
 * <p>鉴权：登录成功后签发管理员 JWT（role=ADMIN），以 HttpOnly Cookie 下发；
 * {@link AdminAuthInterceptor} 守护除登录页外的全部 /admin/** 路径。
 */
@Controller
@RequestMapping("/admin")
public class AdminWebController {

    private final AdminService adminService;
    private final JwtService jwtService;
    private final RiskApi riskApi;
    /** HTTPS 部署时置 true（compose 设 ADMIN_COOKIE_SECURE=true），本地 HTTP 调试保持 false。 */
    private final boolean secureCookie;

    public AdminWebController(AdminService adminService, JwtService jwtService, RiskApi riskApi,
                              @Value("${app.admin.cookie-secure:false}") boolean secureCookie) {
        this.adminService = adminService;
        this.jwtService = jwtService;
        this.riskApi = riskApi;
        this.secureCookie = secureCookie;
    }

    private long adminId(HttpServletRequest request) {
        Object id = request.getAttribute("adminId");
        return id instanceof Long value ? value : 0L;
    }

    // ---------- 登录 ----------

    @GetMapping("/login")
    public String loginPage() {
        return "admin/login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String username, @RequestParam String password,
                        HttpServletRequest request, HttpServletResponse response, Model model) {
        // 失败锁定（尝试前检查）+ 失败计数，与 API 登录同口径
        String ip = ClientIps.of(request);
        riskApi.checkAdminLoginAllowed(username, ip);
        String token;
        try {
            token = adminService.login(username, password);
        } catch (Exception ex) {
            riskApi.onAdminLoginFailure(username, ip);
            model.addAttribute("error", "用户名或密码错误");
            return "admin/login";
        }
        // SameSite=Lax + 可配置 Secure（HttpOnly 原有）
        ResponseCookie cookie = ResponseCookie.from(AdminAuthInterceptor.COOKIE_NAME, token)
                .httpOnly(true)
                .path("/")
                .maxAge(24 * 3600)
                .sameSite("Lax")
                .secure(secureCookie)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return "redirect:/admin/dashboard";
    }

    @PostMapping("/logout")
    public String logout(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(AdminAuthInterceptor.COOKIE_NAME, "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .secure(secureCookie)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return "redirect:/admin/login";
    }

    // ---------- 页面 ----------

    @GetMapping({"/", "/dashboard"})
    public String dashboard(Model model) {
        model.addAttribute("stats", adminService.stats());
        model.addAttribute("cluster", adminService.clusterView());
        model.addAttribute("riskOpen", adminService.countEvents("OPEN"));
        return "admin/dashboard";
    }

    @GetMapping("/users")
    public String users(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("users", adminService.listUsers(page, 20));
        return "admin/users";
    }

    @PostMapping("/users/{userId}/ban")
    public String ban(@PathVariable long userId, HttpServletRequest request) {
        adminService.setUserStatus(adminId(request), userId, 0);
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{userId}/unban")
    public String unban(@PathVariable long userId, HttpServletRequest request) {
        adminService.setUserStatus(adminId(request), userId, 1);
        return "redirect:/admin/users";
    }

    @GetMapping("/battles")
    public String battles(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("battles", adminService.listBattleRecords(page, 20));
        return "admin/battles";
    }

    @GetMapping("/risk")
    public String risk(@RequestParam(defaultValue = "OPEN") String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("status", status);
        model.addAttribute("events", adminService.listRiskEvents(status, page, 20));
        return "admin/risk";
    }

    @PostMapping("/risk/{eventId}/resolve")
    public String resolveRisk(@PathVariable long eventId, @RequestParam String status,
                              HttpServletRequest request) {
        adminService.resolveRiskEvent(adminId(request), eventId, status);
        return "redirect:/admin/risk";
    }

    @GetMapping("/lua")
    public String lua(Model model) {
        model.addAttribute("lua", adminService.luaStatus());
        return "admin/lua";
    }

    @PostMapping("/lua/reload")
    public String reloadLua(@RequestParam(defaultValue = "combat") String target,
                            @RequestParam(required = false) String script, Model model) {
        boolean ok = adminService.reloadLua(target, script);
        String name = "match".equals(target) ? "匹配策略" : "战斗公式";
        model.addAttribute("reloadResult", ok
                ? name + "热更新成功（已广播至集群，其余节点异步生效）"
                : name + "热更新失败（脚本校验未通过，已保留旧脚本）");
        model.addAttribute("lua", adminService.luaStatus());
        return "admin/lua";
    }
}
