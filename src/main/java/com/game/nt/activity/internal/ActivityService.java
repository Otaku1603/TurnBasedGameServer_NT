package com.game.nt.activity.internal;

import com.game.nt.shared.push.ClientPushApi;
import com.game.nt.identity.IdentityApi;
import com.game.nt.proto.GameProto.AnnouncementPush;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.MessageType;
import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动服务：每日签到 + 公告。
 *
 * <p>签到并发安全依赖 {@code t_sign_in} 的 {@code (user_id, sign_date)} 唯一键 +
 * {@code INSERT IGNORE}：重复请求影响行数为 0，直接判定为已签到。
 */
@Service
public class ActivityService {

    private static final Logger log = LoggerFactory.getLogger(ActivityService.class);

    /** 签到基础奖励与连签加成（上限 7 天后不再增长） */
    private static final long BASE_REWARD_GOLD = 100;
    private static final long STREAK_BONUS_GOLD = 50;
    private static final int MAX_STREAK_BONUS_DAYS = 7;

    public record SignInResult(int streak, long rewardGold) {
    }

    public record SignInStatus(boolean signedToday, int streak) {
    }

    public record AnnouncementView(long id, String title, String content, LocalDateTime createdAt) {
    }

    private final SignInMapper signInMapper;
    private final AnnouncementMapper announcementMapper;
    private final IdentityApi identityApi;
    private final ClientPushApi clientPushApi;
    private final com.game.nt.quest.QuestApi questApi;

    public ActivityService(SignInMapper signInMapper, AnnouncementMapper announcementMapper,
                           IdentityApi identityApi, ClientPushApi clientPushApi,
                           com.game.nt.quest.QuestApi questApi) {
        this.signInMapper = signInMapper;
        this.announcementMapper = announcementMapper;
        this.identityApi = identityApi;
        this.clientPushApi = clientPushApi;
        this.questApi = questApi;
    }

    // ==================== 签到 ====================

    @Transactional
    public SignInResult signIn(long userId) {
        identityApi.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "玩家不存在"));

        LocalDate today = LocalDate.now();
        LocalDate last = signInMapper.lastSignDate(userId);

        int streak;
        if (last == null) {
            streak = 1;
        } else if (last.equals(today)) {
            throw new BusinessException(ErrorCode.CONFLICT, "今天已经签到过了");
        } else if (last.equals(today.minusDays(1))) {
            streak = nz(signInMapper.lastStreak(userId)) + 1;
        } else {
            streak = 1;
        }

        long reward = BASE_REWARD_GOLD + STREAK_BONUS_GOLD * Math.min(streak, MAX_STREAK_BONUS_DAYS);

        // 唯一键保证"每天一次"；并发重复提交时影响行数为 0
        if (signInMapper.insert(userId, today, streak, reward) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "今天已经签到过了");
        }
        identityApi.addGold(userId, reward);
        // 跨模块联动：签到行为驱动任务进度
        questApi.addProgress(userId, "DAILY_SIGN_IN", 1);

        log.info("签到成功: userId={}, streak={}, reward={}", userId, streak, reward);
        return new SignInResult(streak, reward);
    }

    public SignInStatus signInStatus(long userId) {
        LocalDate last = signInMapper.lastSignDate(userId);
        boolean signedToday = last != null && last.equals(LocalDate.now());
        return new SignInStatus(signedToday, signedToday ? nz(signInMapper.lastStreak(userId)) : 0);
    }

    // ==================== 公告 ====================

    public List<AnnouncementView> listActiveAnnouncements(int limit) {
        int safe = Math.min(Math.max(limit, 1), 50);
        return announcementMapper.listActive(safe).stream()
                .map(a -> new AnnouncementView(a.getId(), a.getTitle(), a.getContent(), a.getCreatedAt()))
                .toList();
    }

    /** 发布公告并实时广播（后台调用）。 */
    @Transactional
    public long publishAnnouncement(String title, String content, LocalDateTime startAt, LocalDateTime endAt) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "公告标题不能为空");
        }
        AnnouncementEntity entity = new AnnouncementEntity();
        entity.setTitle(title);
        entity.setContent(content == null ? "" : content);
        entity.setActive(1);
        entity.setStartAt(startAt);
        entity.setEndAt(endAt);
        announcementMapper.insert(entity);

        int delivered = clientPushApi.broadcast(GameMessage.newBuilder()
                .setType(MessageType.ANNOUNCEMENT_PUSH)
                .setAnnouncementPush(AnnouncementPush.newBuilder()
                        .setId(entity.getId())
                        .setTitle(entity.getTitle())
                        .setContent(entity.getContent())
                        .build())
                .build());

        log.info("公告已发布: id={}, 触达在线用户={}", entity.getId(), delivered);
        return entity.getId();
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
