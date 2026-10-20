package com.game.nt.shared.push;

import com.game.nt.proto.GameProto.GameMessage;

/**
 * 客户端推送端口（输出端口 / Output Port）。
 *
 * <p>为什么放在 {@code shared} 而不是 {@code gateway}：
 * 推送是多个领域模块（battle / mail / match / activity / social）都需要的<b>能力</b>，
 * 若定义在 gateway 中，就会出现 {@code gateway ↔ battle} 这类<b>循环依赖</b>
 * （由 Spring Modulith 的架构测试强制阻止）。
 * 把它下沉到共享内核后，各模块只依赖这个抽象端口，gateway 负责实现 —— 符合依赖倒置。
 */
public interface ClientPushApi {

    /**
     * 向指定用户推送消息。
     *
     * @return true=已提交到连接（不代表对端已收到）；false=用户不在线或连接不可写（背压丢弃）
     */
    boolean sendToUser(long userId, GameMessage message);

    /** 用户是否在线（存在活跃会话）。 */
    boolean isOnline(long userId);

    /** 当前在线人数。 */
    int onlineCount();

    /** 在线用户 ID 快照（只读副本）。 */
    java.util.Set<Long> onlineUserIds();

    /**
     * 向所有在线用户广播（如系统公告）。返回成功提交的连接数。
     * 注意：单机实现；多实例需接入跨节点广播（路线图 A9）。
     */
    int broadcast(GameMessage message);
}
