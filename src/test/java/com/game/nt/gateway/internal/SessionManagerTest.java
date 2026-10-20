package com.game.nt.gateway.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话管理器的跨节点顶号能力。
 *
 * <p>关键契约：{@code kickLocal} 关闭连接并摘除会话映射，但**保留 Channel 身份属性**——
 * 让 {@code channelInactive} 照常触发集群路由解绑（COMPARE_AND_DEL 保证不误删新路由）。
 */
class SessionManagerTest {

    @Test
    void kickLocalClosesSessionAndClearsMaps() {
        SessionManager sessions = new SessionManager(new SimpleMeterRegistry());
        EmbeddedChannel channel = new EmbeddedChannel();
        sessions.bind(1L, channel);

        assertTrue(sessions.isOnline(1L));
        assertTrue(sessions.kickLocal(1L), "本节点存在该会话，应返回 true");

        assertFalse(sessions.isOnline(1L), "顶号后会话应不可见");
        // 身份属性刻意保留，供 channelInactive 走解绑与路由清理
        assertNotNull(sessions.userIdOf(channel), "Channel 身份属性应保留");
        assertFalse(sessions.kickLocal(1L), "重复顶号对不存在的会话应返回 false");
    }

    @Test
    void kickLocalReturnsFalseForUnknownUser() {
        SessionManager sessions = new SessionManager(new SimpleMeterRegistry());
        assertFalse(sessions.kickLocal(404L));
    }

    @Test
    void bindSameUserLocallyKicksOldChannel() {
        SessionManager sessions = new SessionManager(new SimpleMeterRegistry());
        EmbeddedChannel old = new EmbeddedChannel();
        EmbeddedChannel fresh = new EmbeddedChannel();

        sessions.bind(9L, old);
        sessions.bind(9L, fresh);

        assertTrue(sessions.isOnline(9L));
        assertFalse(old.isActive(), "同账号本地二次登录应关闭旧连接");
    }
}
