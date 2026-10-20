package com.game.nt.risk.internal;

import com.game.nt.risk.RiskApi;
import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * 风控实现。
 *
 * <p><b>限流</b>：Redis ZSET 滑动窗口（member=时间戳），一条管道内"清理 + 计数"，
 * 超限直接抛 {@code RATE_LIMITED}。窗口天然跨节点共享（集群下同样生效）。
 *
 * <p><b>异常检测</b>：机器操作节奏——把相邻两次战斗操作的时间差记录下来，
 * 连续 N 次间隔低于人类反应下限即落事件（不阻断，交给 GM 看面板）。
 */
@Service
public class RiskService implements RiskApi {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    private static final String RL_PREFIX = "risk:rl:";

    /** 战斗操作：10 秒最多 30 次（含心跳外所有指令级别的操作） */
    private static final int ACTION_LIMIT = 30;
    private static final long ACTION_WINDOW_MS = 10_000;
    /** 匹配加入：1 分钟最多 10 次 */
    private static final int MATCH_LIMIT = 10;
    private static final long MATCH_WINDOW_MS = 60_000;
    /** 登录失败：10 分钟同用户最多 10 次；同 IP 最多 30 次 */
    private static final int LOGIN_USER_LIMIT = 10;
    private static final int LOGIN_IP_LIMIT = 30;
    private static final long LOGIN_WINDOW_MS = 600_000;
    /** 机器节奏：连续 5 次操作间隔 < 150ms */
    private static final int CADENCE_STRIKES = 5;
    private static final long CADENCE_MIN_GAP_MS = 150;
    private static final long CADENCE_STRIKE_TTL_MS = 60_000;

    private final StringRedisTemplate redis;
    private final RiskMapper riskMapper;
    private final Counter eventCounter;

    public RiskService(StringRedisTemplate redis, RiskMapper riskMapper, MeterRegistry registry) {
        this.redis = redis;
        this.riskMapper = riskMapper;
        this.eventCounter = Counter.builder("nt.risk.events.total")
                .description("风控事件累计数")
                .register(registry);
    }

    // ==================== 限流钩子 ====================

    @Override
    public void onBattleAction(long userId) {
        checkRate("action:" + userId, ACTION_LIMIT, ACTION_WINDOW_MS);
        detectMachineCadence(userId);
    }

    @Override
    public void onMatchJoin(long userId) {
        checkRate("match:" + userId, MATCH_LIMIT, MATCH_WINDOW_MS);
    }

    @Override
    public void onLoginFailure(String username, String ip) {
        boolean userOver = !checkRate("login:u:" + username, LOGIN_USER_LIMIT, LOGIN_WINDOW_MS, true);
        boolean ipOver = ip != null && !checkRate("login:ip:" + ip, LOGIN_IP_LIMIT, LOGIN_WINDOW_MS, true);
        if (userOver || ipOver) {
            recordEvent("LOGIN_BURST", null, "HIGH",
                    "username=%s, ip=%s, userOver=%s, ipOver=%s".formatted(username, ip, userOver, ipOver));
        }
    }

    // ==================== 异常检测 ====================

    private void detectMachineCadence(long userId) {
        String lastKey = RL_PREFIX + "lastaction:" + userId;
        String strikeKey = RL_PREFIX + "cadence:" + userId;
        long now = System.currentTimeMillis();
        try {
            String lastStr = redis.opsForValue().getAndSet(lastKey, String.valueOf(now));
            redis.expire(lastKey, Duration.ofSeconds(300));
            if (lastStr == null) {
                return;
            }
            long gap = now - Long.parseLong(lastStr);
            if (gap >= 0 && gap < CADENCE_MIN_GAP_MS) {
                // 相邻两次操作间隔低于人类反应下限，累计可疑连击
                Long strikes = redis.opsForValue().increment(strikeKey);
                if (strikes != null && strikes == 1L) {
                    redis.expire(strikeKey, Duration.ofMillis(CADENCE_STRIKE_TTL_MS));
                }
                if (strikes != null && strikes >= CADENCE_STRIKES) {
                    redis.delete(strikeKey);
                    recordEvent("ACTION_CADENCE", userId, "MEDIUM",
                            "连续 %d 次操作间隔 < %dms（疑似脚本）".formatted(CADENCE_STRIKES, CADENCE_MIN_GAP_MS));
                }
            } else {
                redis.delete(strikeKey);
            }
        } catch (NumberFormatException ignored) {
            // 时间戳损坏，重置即可
            redis.delete(lastKey);
        } catch (Exception ex) {
            log.debug("节奏检测跳过（Redis 不可用）: {}", ex.getMessage());
        }
    }
    private void checkRate(String key, int limit, long windowMs) {
        checkRate(key, limit, windowMs, false);
    }

    private boolean checkRate(String key, int limit, long windowMs, boolean silent) {
        long now = System.currentTimeMillis();
        try {
            String fullKey = RL_PREFIX + key;
            redis.opsForZSet().removeRangeByScore(fullKey, 0, now - windowMs);
            Long count = redis.opsForZSet().zCard(fullKey);
            if (count != null && count >= limit) {
                if (!silent) {
                    throw new BusinessException(ErrorCode.RATE_LIMITED, "操作过于频繁，请稍后再试");
                }
                return false;
            }
            redis.opsForZSet().add(fullKey, String.valueOf(now), now);
            redis.expire(fullKey, Duration.ofMillis(windowMs * 2));
            return true;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            // Redis 不可用时降级放行（可用性优先，风控审计照常）
            log.debug("限流检查跳过（Redis 不可用）: {}", ex.getMessage());
            return true;
        }
    }

    // ==================== 事件 ====================

    @Override
    @Transactional
    public void recordEvent(String type, Long userId, String severity, String detail) {
        try {
            riskMapper.insert(userId, type, severity,
                    detail != null && detail.length() > 1000 ? detail.substring(0, 1000) : detail);
            eventCounter.increment();
            log.warn("风控事件: type={}, userId={}, severity={}, detail={}", type, userId, severity, detail);
        } catch (Exception ex) {
            log.error("风控事件落库失败: type={}", type, ex);
        }
    }

    @Override
    public List<RiskEventView> listEvents(String status, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        String st = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status) ? null : status.toUpperCase();
        return riskMapper.page(st, safePage * safeSize, safeSize).stream()
                .map(row -> new RiskEventView(row.getId(), row.getUserId(), row.getType(),
                        row.getSeverity(), row.getDetail(), row.getStatus(), row.getCreatedAt()))
                .toList();
    }

    @Override
    public long countEvents(String status) {
        String st = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status) ? null : status.toUpperCase();
        return riskMapper.count(st);
    }

    @Override
    @Transactional
    public void resolveEvent(long eventId, String status, long adminId) {
        if (!"RESOLVED".equals(status) && !"DISMISSED".equals(status)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "非法的处置状态: " + status);
        }
        if (riskMapper.updateStatus(eventId, status) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "风控事件不存在");
        }
        log.info("风控事件处置: eventId={}, status={}, adminId={}", eventId, status, adminId);
    }
}
