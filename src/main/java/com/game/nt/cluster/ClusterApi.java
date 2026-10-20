package com.game.nt.cluster;

import com.game.nt.proto.GameProto.GameMessage;

import java.util.List;

/**
 * 集群能力（模块 API）。
 *
 * <p>"跨服"在本项目中的落点：多个服务端节点共享同一 Redis 与数据库，
 * 玩家长连接（WS）归属各自节点；匹配、聊天、邮件等业务不感知节点，
 * 推送与战斗指令由本模块按路由表投递到正确节点。
 */
public interface ClusterApi {

    /** 本节点 ID。 */
    String selfNodeId();

    /**
     * 跨节点推送：把消息投递到目标用户所属节点（不投本节点——本节点由网关直接发送）。
     *
     * @return true = 已按路由投出；false = 无路由（用户不在线或路由失效）
     */
    boolean forwardToUser(long userId, GameMessage message);

    /** 把战斗指令转发到战斗归属节点。 */
    void forwardCommand(ClusterCommand command);

    /** 广播到集群所有节点（各节点自行投递本地会话；本节点由调用方自行处理）。 */
    void publishBroadcast(GameMessage message);

    /** 登录绑定：route{userId} -> 本节点。 */
    void bindUserRoute(long userId);

    /** 断开解绑：仅当路由仍指向本节点时删除（防误删顶号后的新路由）。 */
    void unbindUserRoute(long userId);

    /**
     * 跨节点顶号：通知 {@code nodeId} 关闭该用户的旧会话。
     *
     * <p>必须在 {@link #bindUserRoute(long)} 之后调用——否则旧节点断开时会执行
     * COMPARE_AND_DEL，可能删掉尚未被新节点覆盖的路由。
     */
    void kickUserOnNode(String nodeId, long userId);

    /** 广播 Lua 热更到全集群（本节点由调用方先自行 apply；发起节点收到广播后跳过）。 */
    void broadcastLuaReload(String target, String script);

    /** 用户当前归属节点；null = 无路由。 */
    String nodeOfUser(long userId);

    /** 登记战斗归属节点（创建战斗时调用）。 */
    void bindBattleHome(String battleId);

    /** 战斗归属节点；null = 未知（按本地处理）。 */
    String nodeOfBattle(String battleId);

    /** 登记"用户正在进行哪场战斗"（创建战斗时调用，双方都登记）。 */
    void bindUserBattle(long userId, String battleId);

    /** 战斗结束清除登记（仅当登记仍指向该场战斗时才清除，防误删新一局的映射）。 */
    void unbindUserBattle(long userId, String battleId);

    /** 用户当前进行中的战斗 ID；null = 无。跨节点断线/重连路由依赖此表。 */
    String battleOfUser(long userId);

    /** 集群存活节点列表。 */
    List<NodeView> liveNodes();

    /** 全集群路由在线用户（SCAN route 表；GM 全服邮件等低频场景使用）。 */
    List<Long> routedOnlineUserIds();

    /** 集群节点视图。 */
    record NodeView(String nodeId, long startedAtEpochMs, String startedAtText) {
    }
}
