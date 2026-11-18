package com.game.nt.rank.internal;

import com.game.nt.shared.web.CurrentUserSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 排行榜接口。 */
@RestController
@RequestMapping("/api/rank")
public class RankController {

    private final RankService rankService;
    private final CurrentUserSupport currentUser;

    public RankController(RankService rankService, CurrentUserSupport currentUser) {
        this.rankService = rankService;
        this.currentUser = currentUser;
    }

    @GetMapping("/elo")
    public RankService.RankBoard topElo(@RequestParam(defaultValue = "20") int limit) {
        return rankService.topElo(limit);
    }

    @GetMapping("/gold")
    public RankService.RankBoard topGold(@RequestParam(defaultValue = "20") int limit) {
        return rankService.topGold(limit);
    }

    @GetMapping("/me")
    public Map<String, Integer> myRank(@RequestHeader(value = "Authorization", required = false) String auth) {
        return Map.of("rank", rankService.myEloRank(currentUser.requireUserId(auth)));
    }
}
