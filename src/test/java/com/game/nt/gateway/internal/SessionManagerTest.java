package com.game.nt.gateway.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * 回归：同节点顶号后，旧连接的解绑必须报告"非当前会话"，
     * 让 channelInactive 跳过集群路由清理与战斗断线标记（否则会误删新会话路由）。
     */
    @Test
    void unbindSupersededChannelReportsNotCurrentSession() {
        SessionManager sessions = new SessionManager(new SimpleMeterRegistry());
        EmbeddedChannel old = new EmbeddedChannel();
        EmbeddedChannel fresh = new EmbeddedChannel();

        sessions.bind(9L, old);
        sessions.bind(9L, fresh);

        assertFalse(sessions.unbind(old), "被顶掉的旧连接解绑时应报告非当前会话");
        assertTrue(sessions.isOnline(9L), "新会话不受旧连接断开影响");
        assertTrue(sessions.unbind(fresh), "当前会话正常断开应报告是当前会话");
        assertFalse(sessions.isOnline(9L));
    }

    /**
     * 回归：同一连接先登录 A 再登录 B 后，A 的映射必须被清理——
     * A 不再"在线"、A 的推送不会串到该连接；连接断开只解绑 B。
     */
    @Test
    void bindDifferentUserOnSameChannelClearsOldMapping() {
        SessionManager sessions = new SessionManager(new SimpleMeterRegistry());
        EmbeddedChannel channel = new EmbeddedChannel();

        sessions.bind(1L, channel);
        sessions.bind(2L, channel);

        assertFalse(sessions.isOnline(1L), "旧账号映射应被清理，不再视为在线");
        assertTrue(sessions.isOnline(2L));
        assertEquals(2L, sessions.userIdOf(channel), "连接身份应指向新账号");

        assertTrue(sessions.unbind(channel), "断开时应报告是 userId=2 的当前会话");
        assertFalse(sessions.isOnline(2L));
        assertFalse(sessions.isOnline(1L), "旧账号不得因该连接断开而复活映射");
    }
}
