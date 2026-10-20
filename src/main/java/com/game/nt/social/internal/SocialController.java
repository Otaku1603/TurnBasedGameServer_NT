package com.game.nt.social.internal;

import com.game.nt.shared.web.CurrentUserSupport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 社交（好友 + 聊天）HTTP 接口。 */
@RestController
@RequestMapping("/api/social")
public class SocialController {

    public record ChatSendRequest(@NotBlank String content) {
    }

    public record PrivateChatRequest(@NotNull Long toId, @NotBlank String content) {
    }

    private final SocialService socialService;
    private final CurrentUserSupport currentUser;

    public SocialController(SocialService socialService, CurrentUserSupport currentUser) {
        this.socialService = socialService;
        this.currentUser = currentUser;
    }

    // ---------- 玩家资料 ----------

    @GetMapping("/profile")
    public SocialService.ProfileView myProfile(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        long me = currentUser.requireUserId(auth);
        return socialService.profile(me, me);
    }

    @GetMapping("/profile/{userId}")
    public SocialService.ProfileView profile(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long userId) {
        return socialService.profile(currentUser.requireUserId(auth), userId);
    }

    // ---------- 好友 ----------

    @GetMapping("/friends")
    public List<SocialService.FriendView> friends(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return socialService.listFriends(currentUser.requireUserId(auth));
    }

    @GetMapping("/friends/requests")
    public List<SocialService.FriendView> friendRequests(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return socialService.listPendingRequests(currentUser.requireUserId(auth));
    }

    @PostMapping("/friends/request")
    public Map<String, Boolean> sendRequest(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam long toId) {
        socialService.sendFriendRequest(currentUser.requireUserId(auth), toId);
        return Map.of("success", true);
    }

    @PostMapping("/friends/accept")
    public Map<String, Boolean> accept(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam long fromId) {
        socialService.acceptFriendRequest(currentUser.requireUserId(auth), fromId);
        return Map.of("success", true);
    }

    @PostMapping("/friends/reject")
    public Map<String, Boolean> reject(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam long fromId) {
        socialService.rejectFriendRequest(currentUser.requireUserId(auth), fromId);
        return Map.of("success", true);
    }

    @DeleteMapping("/friends/{friendId}")
    public Map<String, Boolean> deleteFriend(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long friendId) {
        socialService.deleteFriend(currentUser.requireUserId(auth), friendId);
        return Map.of("success", true);
    }

    // ---------- 聊天 ----------

    @PostMapping("/chat/world")
    public SocialService.ChatView sendWorld(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @Valid @RequestBody ChatSendRequest request) {
        return socialService.sendWorldChat(currentUser.requireUserId(auth), request.content());
    }

    @PostMapping("/chat/private")
    public SocialService.ChatView sendPrivate(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @Valid @RequestBody PrivateChatRequest request) {
        return socialService.sendPrivateChat(currentUser.requireUserId(auth), request.toId(), request.content());
    }

    @GetMapping("/chat/world/recent")
    public List<SocialService.ChatView> recentWorld(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "30") int limit) {
        currentUser.requireUserId(auth);
        return socialService.recentWorld(limit);
    }

    @GetMapping("/chat/private/{otherId}/recent")
    public List<SocialService.ChatView> recentPrivate(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long otherId,
            @RequestParam(defaultValue = "30") int limit) {
        return socialService.recentPrivate(currentUser.requireUserId(auth), otherId, limit);
    }
}
