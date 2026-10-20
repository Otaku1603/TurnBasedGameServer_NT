package com.game.nt.admin.internal;

import com.game.nt.shared.web.CurrentUserSupport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GM 后台接口。除登录外全部要求管理员令牌（role=ADMIN）。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record AdminLoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record SendMailRequest(Long receiverId, @NotBlank String title, String content, Long gold) {
    }

    private final AdminService adminService;
    private final CurrentUserSupport currentUser;

    public AdminController(AdminService adminService, CurrentUserSupport currentUser) {
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    @PostMapping("/login")
    public Map<String, String> login(@Valid @RequestBody AdminLoginRequest request) {
        String token = adminService.login(request.username(), request.password());
        return Map.of("token", token);
    }

    @GetMapping("/users")
    public Map<String, Object> users(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        currentUser.requireAdminId(auth);
        return adminService.listUsers(page, size);
    }

    @PostMapping("/users/{userId}/ban")
    public Map<String, Boolean> ban(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long userId) {
        adminService.setUserStatus(currentUser.requireAdminId(auth), userId, 0);
        return Map.of("success", true);
    }

    @PostMapping("/users/{userId}/unban")
    public Map<String, Boolean> unban(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long userId) {
        adminService.setUserStatus(currentUser.requireAdminId(auth), userId, 1);
        return Map.of("success", true);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        currentUser.requireAdminId(auth);
        return adminService.stats();
    }

    @PostMapping("/mail")
    public Map<String, Integer> sendMail(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @Valid @RequestBody SendMailRequest request) {
        long adminId = currentUser.requireAdminId(auth);
        int sent = adminService.sendMail(adminId, request.receiverId(), request.title(),
                request.content(), request.gold() == null ? 0 : request.gold());
        return Map.of("sent", sent);
    }

    // ---------- 风控 ----------

    @GetMapping("/risk/events")
    public Map<String, Object> riskEvents(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        currentUser.requireAdminId(auth);
        return adminService.listRiskEvents(status, page, size);
    }

    @PostMapping("/risk/events/{eventId}/resolve")
    public Map<String, Boolean> resolveRiskEvent(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long eventId,
            @RequestParam String status) {
        long adminId = currentUser.requireAdminId(auth);
        adminService.resolveRiskEvent(adminId, eventId, status);
        return Map.of("success", true);
    }

    /** 玩家 token 访问 /api/admin/... 已被 requireAdminId 挡下；本接口仅用于联调排查。 */
    @GetMapping("/cluster")
    public Map<String, Object> cluster(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        currentUser.requireAdminId(auth);
        return adminService.clusterView();
    }
}
