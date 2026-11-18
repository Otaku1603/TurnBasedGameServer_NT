package com.game.nt.risk.internal;

import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归：注册 IP 限流与管理员登录失败锁定。
 * Redis 操作全部 mock，窗口计数直接从 zCard 注入，不依赖真实 Redis。
 */
class RiskServiceLimitTest {

    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zSet;
    private RiskMapper riskMapper;
    private RiskService riskService;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        zSet = mock(ZSetOperations.class);
        riskMapper = mock(RiskMapper.class);
        lenient().when(redis.opsForZSet()).thenReturn(zSet);
        lenient().doReturn(true).when(redis).delete(anyString());
        lenient().when(redis.expire(anyString(), org.mockito.ArgumentMatchers.any(java.time.Duration.class)))
                .thenReturn(true);
        lenient().when(zSet.add(anyString(), anyString(), anyDouble())).thenReturn(true);
        lenient().when(zSet.removeRangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(0L);
        riskService = new RiskService(redis, riskMapper, new SimpleMeterRegistry());
    }

    private void windowCount(String key, long count) {
        when(zSet.zCard("risk:rl:" + key)).thenReturn(count);
    }

    @Test
    void registerBlocksWhenIpWindowExhausted() {
        windowCount("register:ip:1.2.3.4", 20L); // REGISTER_IP_LIMIT = 20

        BusinessException ex = assertThrows(BusinessException.class,
                () -> riskService.onRegister("1.2.3.4"));
        assertEquals(ErrorCode.RATE_LIMITED, ex.code());
    }

    @Test
    void registerAllowsWhenUnderLimit() {
        windowCount("register:ip:1.2.3.4", 19L);
        assertDoesNotThrow(() -> riskService.onRegister("1.2.3.4"));
    }

    @Test
    void adminLoginDeniedAfterUserFailureLimit() {
        windowCount("adminlogin:u:admin", 5L); // ADMIN_USER_LIMIT = 5

        BusinessException ex = assertThrows(BusinessException.class,
                () -> riskService.checkAdminLoginAllowed("admin", "9.9.9.9"));
        assertEquals(ErrorCode.RATE_LIMITED, ex.code());
    }

    @Test
    void adminLoginDeniedAfterIpFailureLimit() {
        windowCount("adminlogin:u:admin", 0L);
        windowCount("adminlogin:ip:9.9.9.9", 20L); // ADMIN_IP_LIMIT = 20

        assertThrows(BusinessException.class,
                () -> riskService.checkAdminLoginAllowed("admin", "9.9.9.9"));
    }

    @Test
    void adminLoginAllowedWhenUnderLimits() {
        windowCount("adminlogin:u:admin", 4L);
        windowCount("adminlogin:ip:9.9.9.9", 0L);
        assertDoesNotThrow(() -> riskService.checkAdminLoginAllowed("admin", "9.9.9.9"));
    }

    @Test
    void adminLoginFailureOverLimitRecordsEvent() {
        windowCount("adminlogin:u:boss", 5L); // 失败时 silent 计数：>= limit → 触发事件

        riskService.onAdminLoginFailure("boss", "9.9.9.9");

        verify(riskMapper).insert(org.mockito.ArgumentMatchers.isNull(),
                contains("ADMIN_LOGIN_BURST"), org.mockito.ArgumentMatchers.eq("HIGH"),
                contains("boss"));
    }

    @Test
    void degradeAllowsWhenRedisUnavailable() {
        when(redis.opsForZSet()).thenThrow(new RuntimeException("redis down"));
        // 可用性优先：Redis 不可用时注册与管理员登录检查都放行
        assertDoesNotThrow(() -> riskService.onRegister("1.2.3.4"));
        assertDoesNotThrow(() -> riskService.checkAdminLoginAllowed("admin", "9.9.9.9"));
    }
}
