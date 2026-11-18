package com.game.nt.guild.internal;

import com.game.nt.shared.web.CurrentUserSupport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 公会接口。 */
@RestController
@RequestMapping("/api/guild")
public class GuildController {

    public record CreateGuildRequest(@NotBlank String name, String notice) {
    }

    public record NoticeRequest(String notice) {
    }

    public record ChatRequest(@NotBlank String content) {
    }

    private final GuildService guildService;
    private final CurrentUserSupport currentUser;

    public GuildController(GuildService guildService, CurrentUserSupport currentUser) {
        this.guildService = guildService;
        this.currentUser = currentUser;
    }

    // ---------- 基础 ----------

    @PostMapping("/create")
    public Map<String, Long> create(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @Valid @RequestBody CreateGuildRequest request) {
        long guildId = guildService.createGuild(currentUser.requireUserId(auth), request.name(), request.notice());
        return Map.of("guildId", guildId);
    }

    @GetMapping("/list")
    public Map<String, Object> list(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        currentUser.requireUserId(auth);
        return guildService.listGuilds(keyword, page, size);
    }

    @PostMapping("/leave")
    public Map<String, Boolean> leave(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        guildService.leave(currentUser.requireUserId(auth));
        return Map.of("success", true);
    }

    @PostMapping("/disband")
    public Map<String, Boolean> disband(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        guildService.disband(currentUser.requireUserId(auth));
        return Map.of("success", true);
    }

    @GetMapping("/{guildId}")
    public Map<String, Object> info(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long guildId) {
        currentUser.requireUserId(auth);
        return guildService.guildInfo(guildId);
    }

    @GetMapping("/{guildId}/members")
    public List<Map<String, Object>> members(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long guildId) {
        currentUser.requireUserId(auth);
        return guildService.members(guildId);
    }

    @GetMapping("/me")
    public Map<String, Object> myGuild(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Long guildId = guildService.guildIdOf(currentUser.requireUserId(auth));
        if (guildId == null) {
            return Map.of("joined", false);
        }
        return Map.of("joined", true, "guild", guildService.guildInfo(guildId));
    }

    // ---------- 入会申请 + 审批 ----------

    @PostMapping("/apply")
    public Map<String, Boolean> apply(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam long guildId) {
        guildService.apply(currentUser.requireUserId(auth), guildId);
        return Map.of("success", true);
    }

    @GetMapping("/applications")
    public List<Map<String, Object>> applications(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return guildService.applications(currentUser.requireUserId(auth));
    }

    @PostMapping("/applications/{userId}/approve")
    public Map<String, Boolean> approve(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long userId) {
        guildService.approve(currentUser.requireUserId(auth), userId);
        return Map.of("success", true);
    }

    @PostMapping("/applications/{userId}/reject")
    public Map<String, Boolean> reject(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long userId) {
        guildService.reject(currentUser.requireUserId(auth), userId);
        return Map.of("success", true);
    }

    // ---------- 成员管理 ----------

    @PostMapping("/members/{userId}/kick")
    public Map<String, Boolean> kick(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long userId) {
        guildService.kick(currentUser.requireUserId(auth), userId);
        return Map.of("success", true);
    }

    @PostMapping("/members/{userId}/promote")
    public Map<String, Boolean> promote(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long userId) {
        guildService.setPosition(currentUser.requireUserId(auth), userId, "OFFICER");
        return Map.of("success", true);
    }

    @PostMapping("/members/{userId}/demote")
    public Map<String, Boolean> demote(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long userId) {
        guildService.setPosition(currentUser.requireUserId(auth), userId, "MEMBER");
        return Map.of("success", true);
    }

    @PostMapping("/transfer")
    public Map<String, Boolean> transfer(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam long userId) {
        guildService.transferLeadership(currentUser.requireUserId(auth), userId);
        return Map.of("success", true);
    }

    @PutMapping("/notice")
    public Map<String, Boolean> updateNotice(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody NoticeRequest request) {
        guildService.updateNotice(currentUser.requireUserId(auth), request.notice());
        return Map.of("success", true);
    }

    // ---------- 公会聊天 ----------

    @PostMapping("/chat")
    public GuildService.GuildChatView chat(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @Valid @RequestBody ChatRequest request) {
        return guildService.sendChat(currentUser.requireUserId(auth), request.content());
    }

    @GetMapping("/chat/recent")
    public List<Map<String, Object>> recentChat(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "30") int limit) {
        return guildService.recentChat(currentUser.requireUserId(auth), limit);
    }
}
