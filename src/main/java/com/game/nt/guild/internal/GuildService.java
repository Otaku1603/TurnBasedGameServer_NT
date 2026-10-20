package com.game.nt.guild.internal;

import com.game.nt.guild.GuildApi;
import com.game.nt.identity.IdentityApi;
import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import com.game.nt.shared.push.ClientPushApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公会服务。
 *
 * <p>原子性要点：
 * <ul>
 *   <li>创建公会先<b>原子扣金币</b>（单条 UPDATE，余额不足直接失败），再落库；整体一个事务</li>
 *   <li>加入/退出用条件 UPDATE 维护 {@code member_count}，不做"读-改-写"</li>
 *   <li>一人一会：{@code t_guild_member.user_id} 唯一索引兜底</li>
 *   <li>审批：{@code resolveApplication} 仅 PENDING 可流转，重复审批影响行数为 0</li>
 *   <li>解散：成员/申请/聊天消息一并清理，避免残留挂在不存在的公会下</li>
 * </ul>
 */
@Service
public class GuildService implements GuildApi {

    private static final Logger log = LoggerFactory.getLogger(GuildService.class);

    private static final long CREATE_COST_GOLD = 1000L;
    private static final int MAX_MEMBERS = 50;
    private static final int NAME_MAX_LENGTH = 16;
    private static final int NOTICE_MAX_LENGTH = 500;
    private static final int CHAT_MAX_LENGTH = 200;

    /** 公会聊天限流：每用户 10 秒内最多 8 条（与社交聊天同口径） */
    private static final int CHAT_LIMIT_PER_WINDOW = 8;
    private static final long CHAT_WINDOW_MILLIS = 10_000L;

    public record GuildChatView(long id, long fromId, String fromName, String content,
                                java.time.LocalDateTime createdAt) {
    }

    private final GuildMapper guildMapper;
    private final IdentityApi identityApi;
    private final ClientPushApi clientPushApi;

    /** 简易滑动窗口限流器（单机；多节点阈值为各节点独立） */
    private final Map<Long, Deque<Long>> chatTimestamps = new ConcurrentHashMap<>();

    public GuildService(GuildMapper guildMapper, IdentityApi identityApi, ClientPushApi clientPushApi) {
        this.guildMapper = guildMapper;
        this.identityApi = identityApi;
        this.clientPushApi = clientPushApi;
    }

    @Transactional
    public long createGuild(long userId, String name, String notice) {
        String guildName = validateName(name);
        identityApi.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "玩家不存在"));
        if (guildMapper.findGuildIdByUser(userId) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "你已加入公会，无法创建");
        }
        if (guildMapper.countByName(guildName) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "公会名已存在");
        }
        // 原子扣费：余额不足直接失败，避免"先建会后扣费"的资损
        if (!identityApi.tryDeductGold(userId, CREATE_COST_GOLD)) {
            throw new BusinessException(ErrorCode.CONFLICT, "创建公会需要 " + CREATE_COST_GOLD + " 金币");
        }

        guildMapper.insertGuild(guildName, userId, notice == null ? "" : notice.strip(), MAX_MEMBERS);
        Long guildId = guildMapper.findIdByName(guildName);
        if (guildId == null) {
            throw new BusinessException(ErrorCode.INTERNAL, "创建公会失败");
        }
        // 关键：会长必须同时成为成员，否则 guildIdOf(userId) 查不到，公会"创建了却不在里面"
        if (guildMapper.addMember(guildId, userId, "LEADER") != 1) {
            throw new BusinessException(ErrorCode.INTERNAL, "创建公会失败：会长入会异常");
        }
        log.info("公会创建成功: guildId={}, name={}, leader={}", guildId, guildName, userId);
        return guildId;
    }

    // ==================== 列表 / 搜索 ====================

    public Map<String, Object> listGuilds(String keyword, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 0);
        String kw = keyword == null ? "" : keyword.strip();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("total", guildMapper.countGuilds(kw));
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("items", guildMapper.listGuilds(kw, safeSize, safePage * safeSize));
        return result;
    }

    // ==================== 入会申请 + 审批 ====================

    @Transactional
    public void apply(long userId, long guildId) {
        if (guildMapper.findGuildIdByUser(userId) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "你已加入公会");
        }
        Map<String, Object> guild = guildMapper.findGuild(guildId);
        if (guild == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "公会不存在");
        }
        int memberCount = ((Number) guild.getOrDefault("memberCount", 0)).intValue();
        int maxMembers = ((Number) guild.getOrDefault("maxMembers", MAX_MEMBERS)).intValue();
        if (memberCount >= maxMembers) {
            throw new BusinessException(ErrorCode.CONFLICT, "公会人数已满");
        }
        guildMapper.upsertApplication(guildId, userId);
        log.info("入会申请: userId={}, guildId={}", userId, guildId);
    }

    /** 待审批列表（会长/官员可见）。 */
    public List<Map<String, Object>> applications(long operatorId) {
        Long guildId = requireOfficer(operatorId);
        return guildMapper.listApplications(guildId);
    }

    @Transactional
    public void approve(long operatorId, long applicantId) {
        Long guildId = requireOfficer(operatorId);
        Map<String, Object> guild = guildMapper.findGuild(guildId);
        int memberCount = ((Number) guild.getOrDefault("memberCount", 0)).intValue();
        int maxMembers = ((Number) guild.getOrDefault("maxMembers", MAX_MEMBERS)).intValue();
        if (memberCount >= maxMembers) {
            throw new BusinessException(ErrorCode.CONFLICT, "公会人数已满");
        }
        if (guildMapper.findGuildIdByUser(applicantId) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "该玩家已加入其他公会");
        }
        if (guildMapper.resolveApplication(guildId, applicantId, "APPROVED") != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "申请不存在或已处理");
        }
        if (guildMapper.addMember(guildId, applicantId, "MEMBER") != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "审批失败：对方已在公会中");
        }
        guildMapper.bumpMemberCount(guildId, 1);
        log.info("入会审批通过: guildId={}, applicant={}, operator={}", guildId, applicantId, operatorId);
    }

    @Transactional
    public void reject(long operatorId, long applicantId) {
        Long guildId = requireOfficer(operatorId);
        if (guildMapper.resolveApplication(guildId, applicantId, "REJECTED") != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "申请不存在或已处理");
        }
    }

    // ==================== 成员管理 ====================

    @Transactional
    public void kick(long operatorId, long targetId) {
        Long guildId = requireOfficer(operatorId);
        if (operatorId == targetId) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "不能踢出自己");
        }
        String targetPosition = guildMapper.findPosition(guildId, targetId);
        if (targetPosition == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "对方不是本公会成员");
        }
        if ("LEADER".equals(targetPosition)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能踢出会长");
        }
        String operatorPosition = guildMapper.findPosition(guildId, operatorId);
        if ("OFFICER".equals(operatorPosition) && "OFFICER".equals(targetPosition)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "官员不能踢出其他官员");
        }
        guildMapper.removeMember(guildId, targetId);
        guildMapper.bumpMemberCount(guildId, -1);
        log.info("成员被踢出公会: guildId={}, target={}, operator={}", guildId, targetId, operatorId);
    }

    /** 任命/撤销官员（仅会长）。position 仅允许 OFFICER / MEMBER。 */
    @Transactional
    public void setPosition(long operatorId, long targetId, String position) {
        if (!"OFFICER".equals(position) && !"MEMBER".equals(position)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "职位只能是 OFFICER 或 MEMBER");
        }
        Long guildId = requireLeader(operatorId);
        if (operatorId == targetId) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "不能修改自己的职位");
        }
        String targetPosition = guildMapper.findPosition(guildId, targetId);
        if (targetPosition == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "对方不是本公会成员");
        }
        if ("LEADER".equals(targetPosition)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能修改会长职位，请使用转让");
        }
        guildMapper.updatePosition(guildId, targetId, position);
        log.info("成员职位变更: guildId={}, target={}, position={}", guildId, targetId, position);
    }

    /** 转让会长（仅会长）：原会长降为 MEMBER，目标升为 LEADER 并更新公会 leader_id。 */
    @Transactional
    public void transferLeadership(long operatorId, long targetId) {
        if (operatorId == targetId) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "不能转让给自己");
        }
        Long guildId = requireLeader(operatorId);
        if (guildMapper.findPosition(guildId, targetId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "对方不是本公会成员");
        }
        guildMapper.updatePosition(guildId, operatorId, "MEMBER");
        guildMapper.updatePosition(guildId, targetId, "LEADER");
        guildMapper.updateLeader(guildId, targetId);
        log.info("会长转让: guildId={}, from={}, to={}", guildId, operatorId, targetId);
    }

    @Transactional
    public void updateNotice(long operatorId, String notice) {
        Long guildId = requireOfficer(operatorId);
        String text = notice == null ? "" : notice.strip();
        if (text.length() > NOTICE_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "公告过长（最多 " + NOTICE_MAX_LENGTH + " 字）");
        }
        guildMapper.updateNotice(guildId, text);
    }

    @Transactional
    public void leave(long userId) {
        Long guildId = guildMapper.findGuildIdByUser(userId);
        if (guildId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "你还没有加入公会");
        }
        String position = guildMapper.findPosition(guildId, userId);
        if ("LEADER".equals(position)) {
            throw new BusinessException(ErrorCode.CONFLICT, "会长不能直接退出，请先解散公会或转让");
        }
        guildMapper.removeMember(guildId, userId);
        guildMapper.bumpMemberCount(guildId, -1);
    }

    /** 解散公会（仅会长）：一并清理成员/申请/聊天，避免残留。 */
    @Transactional
    public void disband(long userId) {
        Long guildId = guildMapper.findGuildIdByUser(userId);
        if (guildId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "你还没有加入公会");
        }
        if (!"LEADER".equals(guildMapper.findPosition(guildId, userId))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有会长可以解散公会");
        }
        guildMapper.deleteMembers(guildId);
        guildMapper.deleteApplications(guildId);
        guildMapper.deleteMessages(guildId);
        guildMapper.deleteGuild(guildId);
        log.info("公会已解散: guildId={}, leader={}", guildId, userId);
    }

    // ==================== 查询 ====================

    public Map<String, Object> guildInfo(long guildId) {
        Map<String, Object> guild = guildMapper.findGuild(guildId);
        if (guild == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "公会不存在");
        }
        return guild;
    }

    public List<Map<String, Object>> members(long guildId) {
        return guildMapper.listMembers(guildId);
    }

    // ==================== 公会聊天 ====================

    @Transactional
    public GuildChatView sendChat(long userId, String content) {
        Long guildId = guildMapper.findGuildIdByUser(userId);
        if (guildId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "你还没有加入公会");
        }
        String name = identityApi.findByUserId(userId)
                .map(IdentityApi.AccountInfo::nickname)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "玩家不存在"));
        String text = sanitizeChat(content);
        checkChatRateLimit(userId);
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("guildId", guildId);
        params.put("fromId", userId);
        params.put("fromName", name);
        params.put("content", text);
        guildMapper.insertMessage(params);
        long messageId = params.get("id") == null ? 0L : ((Number) params.get("id")).longValue();

        // 只推给本公会成员（不在线的静默忽略，登录后经 recent 拉取）
        com.game.nt.proto.GameProto.ChatPush payload = com.game.nt.proto.GameProto.ChatPush.newBuilder()
                .setChannel("GUILD")
                .setFromId(userId)
                .setFromName(name)
                .setContent(text)
                .setServerTime(System.currentTimeMillis())
                .build();
        com.game.nt.proto.GameProto.GameMessage push = com.game.nt.proto.GameProto.GameMessage.newBuilder()
                .setType(com.game.nt.proto.GameProto.MessageType.CHAT_PUSH)
                .setChatPush(payload)
                .build();
        for (Long memberId : guildMapper.listMemberIds(guildId)) {
            clientPushApi.sendToUser(memberId, push);
        }
        return new GuildChatView(messageId, userId, name, text, java.time.LocalDateTime.now());
    }

    public List<Map<String, Object>> recentChat(long userId, int limit) {
        Long guildId = guildMapper.findGuildIdByUser(userId);
        if (guildId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "你还没有加入公会");
        }
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return guildMapper.recentMessages(guildId, safeLimit);
    }

    // ==================== GuildApi ====================

    @Override
    public Long guildIdOf(long userId) {
        return guildMapper.findGuildIdByUser(userId);
    }

    @Override
    public String guildName(long guildId) {
        return guildMapper.findName(guildId);
    }

    /** 会长/官员权限校验，返回其公会 ID。 */
    private Long requireOfficer(long operatorId) {
        Long guildId = guildMapper.findGuildIdByUser(operatorId);
        if (guildId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "你还没有加入公会");
        }
        String position = guildMapper.findPosition(guildId, operatorId);
        if (!"LEADER".equals(position) && !"OFFICER".equals(position)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "需要会长或官员权限");
        }
        return guildId;
    }

    /** 仅会长权限校验，返回其公会 ID。 */
    private Long requireLeader(long operatorId) {
        Long guildId = guildMapper.findGuildIdByUser(operatorId);
        if (guildId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "你还没有加入公会");
        }
        if (!"LEADER".equals(guildMapper.findPosition(guildId, operatorId))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有会长可以执行该操作");
        }
        return guildId;
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "公会名不能为空");
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "公会名过长（最多 " + NAME_MAX_LENGTH + " 字）");
        }
        return trimmed;
    }

    private static String sanitizeChat(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "消息内容不能为空");
        }
        String trimmed = content.strip();
        if (trimmed.length() > CHAT_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "消息过长（最多 " + CHAT_MAX_LENGTH + " 字）");
        }
        return trimmed;
    }

    private void checkChatRateLimit(long userId) {
        long now = Instant.now().toEpochMilli();
        Deque<Long> window = chatTimestamps.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > CHAT_WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.size() >= CHAT_LIMIT_PER_WINDOW) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "发言过于频繁，请稍后再试");
            }
            window.addLast(now);
        }
    }
}
