package com.game.nt.social.internal;

import com.game.nt.risk.RiskApi;
import com.game.nt.shared.push.ClientPushApi;
import com.game.nt.guild.GuildApi;
import com.game.nt.identity.IdentityApi;
import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 社交服务：好友 + 聊天。
 *
 * <p>包含一处原项目缺失的能力：<b>聊天频率限制</b>（防刷屏）。
 * 聊天限流统一走 {@link RiskApi} 的 Redis 滑动窗口：多节点共享阈值，
 * 不再需要进程内 Map（旧实现无淘汰、跨节点可绕过）。
 */
@Service
public class SocialService {

    public record FriendView(long userId, String nickname, int eloRating, boolean online) {
    }

    public record ChatView(long id, String channel, long fromId, String fromName, String content, LocalDateTime createdAt) {
    }

    /**
     * 玩家资料（对外投影）。
     *
     * <p>{@code gold} 仅本人可见（查看他人时为 null）。
     */
    public record ProfileView(long userId, String nickname, int eloRating,
                              int winCount, int totalBattles, Long guildId, String guildName, Long gold) {
    }

    private final FriendMapper friendMapper;
    private final ChatMapper chatMapper;
    private final IdentityApi identityApi;
    private final ClientPushApi clientPushApi;
    private final GuildApi guildApi;
    private final RiskApi riskApi;

    public SocialService(FriendMapper friendMapper, ChatMapper chatMapper,
                         IdentityApi identityApi, ClientPushApi clientPushApi, GuildApi guildApi,
                         RiskApi riskApi) {
        this.friendMapper = friendMapper;
        this.chatMapper = chatMapper;
        this.identityApi = identityApi;
        this.clientPushApi = clientPushApi;
        this.guildApi = guildApi;
        this.riskApi = riskApi;
    }

    // ==================== 资料 ====================

    public ProfileView profile(long viewerId, long targetUserId) {
        IdentityApi.AccountInfo account = identityApi.findByUserId(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "玩家不存在"));
        Long guildId = guildApi.guildIdOf(targetUserId);
        String guildName = guildId == null ? null : guildApi.guildName(guildId);
        Long gold = viewerId == targetUserId ? account.gold() : null;
        return new ProfileView(account.userId(), account.nickname(), account.eloRating(),
                account.winCount(), account.totalBattles(), guildId, guildName, gold);
    }

    // ==================== 好友 ====================

    public List<FriendView> listFriends(long userId) {
        return friendMapper.listFriends(userId).stream()
                .map(row -> new FriendView(row.getUserId(), row.getNickname(),
                        nz(row.getEloRating()), clientPushApi.isOnline(row.getUserId())))
                .toList();
    }

    public List<FriendView> listPendingRequests(long userId) {
        return friendMapper.listPendingRequests(userId).stream()
                .map(row -> new FriendView(row.getUserId(), row.getNickname(), nz(row.getEloRating()), false))
                .toList();
    }

    @Transactional
    public void sendFriendRequest(long fromId, long toId) {
        if (fromId == toId) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "不能添加自己为好友");
        }
        identityApi.findByUserId(toId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "目标玩家不存在"));
        if (friendMapper.countRelation(fromId, toId) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "你们已经是好友");
        }
        friendMapper.upsertRequest(fromId, toId);

        // 实时提醒对方（离线则忽略；对方下次登录经 listPendingRequests 拉取）
        String fromName = identityApi.findByUserId(fromId)
                .map(IdentityApi.AccountInfo::nickname).orElse("");
        clientPushApi.sendToUser(toId, com.game.nt.proto.GameProto.GameMessage.newBuilder()
                .setType(com.game.nt.proto.GameProto.MessageType.FRIEND_REQUEST)
                .setFriendRequestPush(com.game.nt.proto.GameProto.FriendRequestPush.newBuilder()
                        .setFromId(fromId)
                        .setFromName(fromName)
                        .build())
                .build());
    }

    @Transactional
    public void acceptFriendRequest(long userId, long fromId) {
        if (friendMapper.resolveRequest(fromId, userId, "ACCEPTED") != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "好友申请不存在或已处理");
        }
        friendMapper.addFriend(userId, fromId);
        friendMapper.addFriend(fromId, userId);
    }

    @Transactional
    public void rejectFriendRequest(long userId, long fromId) {
        if (friendMapper.resolveRequest(fromId, userId, "REJECTED") != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "好友申请不存在或已处理");
        }
    }

    @Transactional
    public void deleteFriend(long userId, long friendId) {
        friendMapper.removeFriend(userId, friendId);
        friendMapper.removeFriend(friendId, userId);
    }

    // ==================== 聊天 ====================

    @Transactional
    public ChatView sendWorldChat(long userId, String content) {
        String name = requireNickname(userId);
        checkRateLimit(userId);
        return persistAndPush(ChatMessageEntity.CHANNEL_WORLD, userId, name, null, content);
    }

    @Transactional
    public ChatView sendPrivateChat(long userId, long toId, String content) {
        if (userId == toId) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "不能给自己发私聊");
        }
        identityApi.findByUserId(toId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "目标玩家不存在"));
        String name = requireNickname(userId);
        checkRateLimit(userId);
        return persistAndPush(ChatMessageEntity.CHANNEL_PRIVATE, userId, name, toId, content);
    }

    public List<ChatView> recentWorld(int limit) {
        return chatMapper.recentByChannel(ChatMessageEntity.CHANNEL_WORLD, clampLimit(limit))
                .stream().map(SocialService::toView).toList();
    }

    public List<ChatView> recentPrivate(long userId, long otherId, int limit) {
        return chatMapper.recentPrivate(userId, otherId, clampLimit(limit))
                .stream().map(SocialService::toView).toList();
    }

    // ==================== 内部 ====================

    private ChatView persistAndPush(String channel, long fromId, String fromName, Long toId, String content) {
        String text = sanitize(content);
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setChannel(channel);
        entity.setFromId(fromId);
        entity.setFromName(fromName);
        entity.setToId(toId);
        entity.setContent(text);
        chatMapper.insert(entity);

        // 实时推送：私聊定向推给收件人；世界频道广播给全部在线（跨节点由集群广播叠加）
        com.game.nt.proto.GameProto.ChatPush payload = com.game.nt.proto.GameProto.ChatPush.newBuilder()
                .setChannel(channel)
                .setFromId(fromId)
                .setFromName(fromName)
                .setContent(text)
                .setServerTime(System.currentTimeMillis())
                .build();
        com.game.nt.proto.GameProto.GameMessage push = com.game.nt.proto.GameProto.GameMessage.newBuilder()
                .setType(com.game.nt.proto.GameProto.MessageType.CHAT_PUSH)
                .setChatPush(payload)
                .build();
        if (ChatMessageEntity.CHANNEL_PRIVATE.equals(channel) && toId != null) {
            clientPushApi.sendToUser(toId, push);
        } else if (ChatMessageEntity.CHANNEL_WORLD.equals(channel)) {
            clientPushApi.broadcast(push);
        }
        return new ChatView(entity.getId(), channel, fromId, fromName, text, LocalDateTime.now());
    }

    private static ChatView toView(ChatMessageEntity e) {
        return new ChatView(e.getId(), e.getChannel(), e.getFromId(), e.getFromName(), e.getContent(), e.getCreatedAt());
    }

    private String requireNickname(long userId) {
        return identityApi.findByUserId(userId)
                .map(IdentityApi.AccountInfo::nickname)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "玩家不存在"));
    }

    /** 聊天内容：去首尾空白 + 长度与空白控制，避免刷屏与注入式内容。 */
    private static String sanitize(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "消息内容不能为空");
        }
        String trimmed = content.strip();
        if (trimmed.length() > 200) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "消息过长（最多 200 字）");
        }
        return trimmed;
    }

    private static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), 100);
    }

    /** 聊天限流：统一走风控 Redis 窗口，超限抛 RATE_LIMITED。 */
    private void checkRateLimit(long userId) {
        riskApi.onChat(userId);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
