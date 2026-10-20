package com.game.nt.cluster;

import com.game.nt.proto.GameProto.GameMessage;

/**
 * 本地投递端口（由 gateway 的 SessionManager 实现）。
 *
 * <p>cluster 模块从 Pub/Sub 收到属于本节点的消息后，只调用本接口投递**本地会话**；
 * 与 {@link com.game.nt.shared.push.ClientPushApi#sendToUser}（本地优先、未命中跨节点转发）刻意分离，
 * 避免集群模块反向依赖网关、也避免转发递归。
 */
public interface ClusterLocalDelivery {

    /** 投递到本节点上的用户会话；用户不在本节点返回 false。 */
    boolean deliverLocal(long userId, GameMessage message);

    /** 投递到本节点全部会话。 */
    int deliverLocalBroadcast(GameMessage message);

    /**
     * 关闭本节点上该用户的会话（跨节点顶号）。
     *
     * <p>只摘除会话映射并断开连接，<b>不清除 Channel 身份属性</b>——
     * 让 {@code channelInactive} 照常走解绑与集群路由清理（COMPARE_AND_DEL 保证不误删新路由）。
     *
     * @return false = 本节点无该用户会话
     */
    boolean kickLocal(long userId);
}
