package com.game.nt.gamedata.internal;

import com.game.nt.shared.web.CurrentUserSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 配置字典接口（只读，需登录）。 */
@RestController
@RequestMapping("/api/gamedata")
public class GamedataController {

    private final GamedataMapper gamedataMapper;
    private final CurrentUserSupport currentUser;

    public GamedataController(GamedataMapper gamedataMapper, CurrentUserSupport currentUser) {
        this.gamedataMapper = gamedataMapper;
        this.currentUser = currentUser;
    }

    @GetMapping("/skills")
    public List<Map<String, Object>> skills(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        currentUser.requireUserId(auth);
        return gamedataMapper.listSkills();
    }

    @GetMapping("/items")
    public List<Map<String, Object>> items(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        currentUser.requireUserId(auth);
        return gamedataMapper.listItems();
    }

    @GetMapping("/character-templates")
    public List<Map<String, Object>> characterTemplates(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        currentUser.requireUserId(auth);
        return gamedataMapper.listCharacterTemplates();
    }
}
