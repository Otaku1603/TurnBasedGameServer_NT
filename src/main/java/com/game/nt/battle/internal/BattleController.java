package com.game.nt.battle.internal;

import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import com.game.nt.shared.web.CurrentUserSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 玩家侧战绩接口：我的历史 / 单局战报。 */
@RestController
@RequestMapping("/api/battle")
public class BattleController {

    private final BattleRecordMapper battleRecordMapper;
    private final CurrentUserSupport currentUser;

    public BattleController(BattleRecordMapper battleRecordMapper, CurrentUserSupport currentUser) {
        this.battleRecordMapper = battleRecordMapper;
        this.currentUser = currentUser;
    }

    /** 我的对战历史（最近 N 局，含胜负与对手）。 */
    @GetMapping("/history")
    public List<Map<String, Object>> history(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "10") int limit) {
        long userId = currentUser.requireUserId(auth);
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        List<Map<String, Object>> rows = battleRecordMapper.listByUser(userId, safeLimit);
        // 补充"相对本人"的字段（我是不是胜者、对手昵称、我的 Elo 变化）
        for (Map<String, Object> row : rows) {
            long winnerId = ((Number) row.get("winnerId")).longValue();
            boolean p1 = ((Number) row.get("player1Id")).longValue() == userId;
            row.put("win", winnerId == userId);
            row.put("opponentId", p1 ? row.get("player2Id") : row.get("player1Id"));
            row.put("opponentNickname", p1 ? row.get("player2Nickname") : row.get("player1Nickname"));
            row.put("eloBefore", p1 ? row.get("player1EloBefore") : row.get("player2EloBefore"));
            row.put("eloAfter", p1 ? row.get("player1EloAfter") : row.get("player2EloAfter"));
            row.remove("player1Nickname");
            row.remove("player2Nickname");
        }
        return rows;
    }

    /** 单局战报（仅该局两名参与者可查看）。 */
    @GetMapping("/report/{battleId}")
    public Map<String, Object> report(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String battleId) {
        long userId = currentUser.requireUserId(auth);
        Map<String, Object> record = battleRecordMapper.findByBattleId(battleId);
        if (record == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "战报不存在");
        }
        long p1 = ((Number) record.get("player1Id")).longValue();
        long p2 = ((Number) record.get("player2Id")).longValue();
        if (userId != p1 && userId != p2) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能查看自己参与的对局战报");
        }
        return record;
    }
}
