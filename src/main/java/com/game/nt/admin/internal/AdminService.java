package com.game.nt.admin.internal;

import com.game.nt.cluster.ClusterApi;
import com.game.nt.cluster.ClusterLuaReloadHandler;
import com.game.nt.identity.IdentityApi;
import com.game.nt.mail.MailApi;
import com.game.nt.match.MatchApi;
import com.game.nt.risk.RiskApi;
import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import com.game.nt.shared.push.ClientPushApi;
import com.game.nt.shared.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GM 后台服务。
 *
 * <p>安全要点：管理员令牌带 {@code role=ADMIN} 声明，玩家令牌无法访问管理接口
 * （原项目的 Lua 管理接口完全无鉴权，属于最严重的安全问题之一）。
 */
@Service
public class AdminService implements ClusterLuaReloadHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final AdminMapper adminMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ClientPushApi clientPushApi;
    private final MatchApi matchApi;
    private final MailApi mailApi;
    private final RiskApi riskApi;
    private final ClusterApi clusterApi;
    private final com.game.nt.shared.lua.LuaSandbox luaSandbox;
    private final com.game.nt.shared.lua.LuaSandbox matchLuaSandbox;

    public AdminService(AdminMapper adminMapper, PasswordEncoder passwordEncoder, JwtService jwtService,
                        ClientPushApi clientPushApi, MatchApi matchApi, MailApi mailApi,
                        RiskApi riskApi, ClusterApi clusterApi,
                        com.game.nt.shared.lua.LuaSandbox luaSandbox,
                        @org.springframework.beans.factory.annotation.Qualifier("matchLuaSandbox")
                        com.game.nt.shared.lua.LuaSandbox matchLuaSandbox) {
        this.adminMapper = adminMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.clientPushApi = clientPushApi;
        this.matchApi = matchApi;
        this.mailApi = mailApi;
        this.riskApi = riskApi;
        this.clusterApi = clusterApi;
        this.luaSandbox = luaSandbox;
        this.matchLuaSandbox = matchLuaSandbox;
    }

    public String login(String username, String rawPassword) {
        if (username == null || rawPassword == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        Map<String, Object> admin = adminMapper.findAdminByUsername(username);
        if (admin == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        String hash = (String) admin.get("passwordHash");
        if (hash == null || !passwordEncoder.matches(rawPassword, hash)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        long adminId = ((Number) admin.get("id")).longValue();
        log.info("管理员登录: adminId={}, username={}", adminId, username);
        return jwtService.issueAdmin(adminId, username);
    }

    public Map<String, Object> listUsers(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        List<Map<String, Object>> items = adminMapper.listUsers(safeSize, safePage * safeSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", adminMapper.countUsers());
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("items", items);
        return result;
    }

    /** 封禁 / 解封。status: 1=正常 0=封禁。 */
    @Transactional
    public void setUserStatus(long adminId, long userId, int status) {
        if (status != 0 && status != 1) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "status 只能是 0 或 1");
        }
        if (adminMapper.setUserStatus(userId, status) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        log.warn("管理员{}用户: adminId={}, userId={}", status == 0 ? "封禁" : "解封", adminId, userId);
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("online", clientPushApi.onlineCount());
        stats.put("users", adminMapper.countUsers());
        stats.put("battleRecords", adminMapper.countBattleRecords());
        stats.put("matchQueue", matchApi.queueSize());
        return stats;
    }

    /**
     * 发系统邮件。
     *
     * @param receiverId 目标玩家；为 null 时发给全体在线玩家
     */
    public int sendMail(long adminId, Long receiverId, String title, String content, long gold) {
        List<MailApi.Attachment> attachments = gold > 0 ? List.of(MailApi.Attachment.gold((int) gold)) : List.of();
        int sent;
        if (receiverId != null) {
            mailApi.sendSystemMail(receiverId, title, content, attachments, Duration.ofDays(30));
            sent = 1;
        } else {
            // 全体在线玩家（离线玩家下次登录时可通过运营脚本补发；全量群发属异步任务范畴）
            sent = 0;
            for (Long onlineUserId : clientPushApi.onlineUserIds()) {
                mailApi.sendSystemMail(onlineUserId, title, content, attachments, Duration.ofDays(30));
                sent++;
            }
        }
        log.info("GM 发送邮件: adminId={}, 目标={}, 封数={}", adminId, receiverId == null ? "全体在线" : receiverId, sent);
        return sent;
    }

    // ---------- 风控 ----------

    public Map<String, Object> listRiskEvents(String status, int page, int size) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("page", page);
        result.put("size", size);
        result.put("total", riskApi.countEvents(status));
        result.put("items", riskApi.listEvents(status, page, size));
        return result;
    }

    public void resolveRiskEvent(long adminId, long eventId, String status) {
        riskApi.resolveEvent(eventId, status, adminId);
    }

    public long countEvents(String status) {
        return riskApi.countEvents(status);
    }

    // ---------- 集群视图 ----------

    public Map<String, Object> clusterView() {
        Map<String, Object> result = new HashMap<>();
        result.put("selfNode", clusterApi.selfNodeId());
        result.put("nodes", clusterApi.liveNodes());
        result.put("routedOnlineUsers", clusterApi.routedOnlineUserIds().size());
        return result;
    }

    // ---------- 战报与 Lua ----------

    public Map<String, Object> listBattleRecords(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        Map<String, Object> result = new HashMap<>();
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("total", adminMapper.countBattleRecords());
        result.put("items", adminMapper.pageBattleRecords(safeSize, safePage * safeSize));
        return result;
    }

    public Map<String, Object> luaStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("ready", luaSandbox.isReady());
        result.put("scriptPath", "lua/damage_formulas.lua");
        result.put("matchReady", matchLuaSandbox.isReady());
        result.put("matchScriptPath", "lua/match_strategy.lua");
        return result;
    }

    /**
     * 热更 Lua 脚本（沙箱校验失败自动保留旧版本）。
     *
     * <p>先在本节点应用；本地成功后再广播到集群其余节点（发起节点收到广播会跳过）。
     * 返回值为<b>本节点</b>结果，远端为异步生效——Pub/Sub 是 fire-and-forget，
     * 若恰有节点在广播窗口内重启而漏收，运维兜底为重启后再点一次热更。
     *
     * @param target combat=战斗公式 / match=匹配策略
     */
    public boolean reloadLua(String target, String newScript) {
        String resolved = target == null ? "combat" : target;
        boolean ok = applyLua(resolved, newScript);
        if (ok) {
            clusterApi.broadcastLuaReload(resolved, newScript);
        }
        return ok;
    }

    /** 集群广播回调：仅在本节点应用（不再次广播，避免风暴）。 */
    @Override
    public void onLuaReload(String target, String script) {
        applyLua(target == null ? "combat" : target, script);
    }

    private boolean applyLua(String target, String newScript) {
        return switch (target) {
            case "match" -> matchLuaSandbox.reload(newScript);
            default -> luaSandbox.reload(newScript);
        };
    }
}
