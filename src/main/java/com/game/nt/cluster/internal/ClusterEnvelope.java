package com.game.nt.cluster.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.game.nt.cluster.ClusterCommand;
import com.game.nt.proto.GameProto.GameMessage;

/**
 * 集群消息信封（Redis Pub/Sub 载荷，JSON 编码）。
 *
 * <ul>
 *   <li>{@code kind=PUSH}：单用户推送，{@code userId} + {@code messageB64}</li>
 *   <li>{@code kind=BROADCAST}：全节点广播，{@code messageB64}（本节点收自己发的广播时跳过，避免重复）</li>
 *   <li>{@code kind=CMD}：战斗指令转发，{@code command}</li>
 *   <li>{@code kind=KICK}：跨节点顶号，{@code userId}（目标节点关闭该用户会话）</li>
 *   <li>{@code kind=LUA_RELOAD}：Lua 热更广播，{@code luaTarget} + {@code luaScript}</li>
 * </ul>
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：滚动升级过程中新老节点字段不一致时不炸，
 * 只忽略无法识别的字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClusterEnvelope(
        String kind,
        String from,
        long userId,
        String messageB64,
        ClusterCommand command,
        String luaTarget,
        String luaScript) {

    public static final String KIND_PUSH = "PUSH";
    public static final String KIND_BROADCAST = "BROADCAST";
    public static final String KIND_CMD = "CMD";
    public static final String KIND_KICK = "KICK";
    public static final String KIND_LUA_RELOAD = "LUA_RELOAD";

    public static ClusterEnvelope push(String from, long userId, GameMessage message) {
        return new ClusterEnvelope(KIND_PUSH, from, userId, encode(message), null, null, null);
    }

    public static ClusterEnvelope broadcast(String from, GameMessage message) {
        return new ClusterEnvelope(KIND_BROADCAST, from, 0, encode(message), null, null, null);
    }

    public static ClusterEnvelope cmd(String from, ClusterCommand command) {
        return new ClusterEnvelope(KIND_CMD, from, 0, null, command, null, null);
    }

    /** 跨节点顶号通知：{@code from}=新会话所在节点，{@code userId}=被顶用户。 */
    public static ClusterEnvelope kick(String from, long userId) {
        return new ClusterEnvelope(KIND_KICK, from, userId, null, null, null, null);
    }

    /** Lua 热更广播：{@code luaScript} 为 null/空白表示各节点从 classpath 重新加载。 */
    public static ClusterEnvelope luaReload(String from, String target, String script) {
        return new ClusterEnvelope(KIND_LUA_RELOAD, from, 0, null, null, target, script);
    }

    private static String encode(GameMessage message) {
        return java.util.Base64.getEncoder().encodeToString(message.toByteArray());
    }

    public GameMessage parseMessage() {
        try {
            return GameMessage.parseFrom(java.util.Base64.getDecoder().decode(messageB64));
        } catch (Exception ex) {
            throw new IllegalStateException("集群消息解码失败", ex);
        }
    }
}
