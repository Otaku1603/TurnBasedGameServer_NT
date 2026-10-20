package com.game.nt.match.internal;

import com.game.nt.shared.web.CurrentUserSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 匹配 HTTP 接口（与 WS 的 MATCH_REQUEST 等价，便于客户端/调试二选一）。 */
@RestController
@RequestMapping("/api/match")
public class MatchController {

    private final MatchService matchService;
    private final CurrentUserSupport currentUser;

    public MatchController(MatchService matchService, CurrentUserSupport currentUser) {
        this.matchService = matchService;
        this.currentUser = currentUser;
    }

    @PostMapping("/join")
    public Map<String, Boolean> join(@RequestHeader(value = "Authorization", required = false) String auth) {
        matchService.join(currentUser.requireUserId(auth));
        return Map.of("success", true);
    }

    @PostMapping("/cancel")
    public Map<String, Boolean> cancel(@RequestHeader(value = "Authorization", required = false) String auth) {
        boolean removed = matchService.cancel(currentUser.requireUserId(auth));
        return Map.of("success", removed);
    }

    @GetMapping("/queue-size")
    public Map<String, Integer> queueSize() {
        return Map.of("size", matchService.queueSize());
    }
}
