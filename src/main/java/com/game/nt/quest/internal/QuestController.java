package com.game.nt.quest.internal;

import com.game.nt.shared.web.CurrentUserSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 任务/成就接口。 */
@RestController
@RequestMapping("/api/quest")
public class QuestController {

    private final QuestService questService;
    private final CurrentUserSupport currentUser;

    public QuestController(QuestService questService, CurrentUserSupport currentUser) {
        this.questService = questService;
        this.currentUser = currentUser;
    }

    /** 我的任务列表（含进度与完成/领取状态）。 */
    @GetMapping("/mine")
    public List<Map<String, Object>> mine(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return questService.listMine(currentUser.requireUserId(auth));
    }

    /** 领取任务奖励。 */
    @PostMapping("/{questId}/claim")
    public Map<String, Object> claim(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable int questId) {
        return questService.claimReward(currentUser.requireUserId(auth), questId);
    }
}
