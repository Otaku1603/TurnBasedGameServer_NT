package com.game.nt.activity.internal;

import com.game.nt.shared.web.CurrentUserSupport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 活动接口：签到 + 公告。 */
@RestController
@RequestMapping("/api/activity")
public class ActivityController {

    public record AnnouncementRequest(@NotBlank String title, String content,
                                      LocalDateTime startAt, LocalDateTime endAt) {
    }

    private final ActivityService activityService;
    private final CurrentUserSupport currentUser;

    public ActivityController(ActivityService activityService, CurrentUserSupport currentUser) {
        this.activityService = activityService;
        this.currentUser = currentUser;
    }

    @PostMapping("/sign-in")
    public ActivityService.SignInResult signIn(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return activityService.signIn(currentUser.requireUserId(auth));
    }

    @GetMapping("/sign-in/status")
    public ActivityService.SignInStatus signInStatus(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return activityService.signInStatus(currentUser.requireUserId(auth));
    }

    @GetMapping("/announcements")
    public List<ActivityService.AnnouncementView> announcements(
            @RequestParam(defaultValue = "10") int limit) {
        return activityService.listActiveAnnouncements(limit);
    }

    @PostMapping("/announcements")
    public Map<String, Long> publish(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @Valid @RequestBody AnnouncementRequest request) {
        currentUser.requireUserId(auth); // 需要登录；管理员角色校验在 admin 模块接入后补齐
        long id = activityService.publishAnnouncement(
                request.title(), request.content(), request.startAt(), request.endAt());
        return Map.of("id", id);
    }
}
