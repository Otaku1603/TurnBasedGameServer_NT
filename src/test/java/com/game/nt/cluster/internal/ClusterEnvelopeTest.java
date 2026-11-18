package com.game.nt.cluster.internal;

import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.MessageType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集群信封序列化契约（滚动升级安全）。
 *
 * <p>守住三件事：
 * <ol>
 *   <li>各 kind 的 JSON 往返不丢字段（含新增的 luaTarget/luaScript）</li>
 *   <li>旧格式 JSON（无 Lua 字段）仍能反序列化，缺失字段为 null</li>
 *   <li>未知字段被忽略（{@code @JsonIgnoreProperties}），新老节点混跑不炸</li>
 * </ol>
 */
class ClusterEnvelopeTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void pushRoundTripPreservesEnvelopeAndMessage() throws Exception {
        GameMessage message = GameMessage.newBuilder()
                .setType(MessageType.MATCH_SUCCESS)
                .setRequestId(7)
                .build();
        ClusterEnvelope envelope = ClusterEnvelope.push("node-1", 42L, message);

        ClusterEnvelope parsed = mapper.readValue(mapper.writeValueAsString(envelope), ClusterEnvelope.class);

        assertEquals(ClusterEnvelope.KIND_PUSH, parsed.kind());
        assertEquals("node-1", parsed.from());
        assertEquals(42L, parsed.userId());
        assertNotNull(parsed.parseMessage());
        assertEquals(MessageType.MATCH_SUCCESS, parsed.parseMessage().getType());
        assertEquals(7, parsed.parseMessage().getRequestId());
    }

    @Test
    void luaReloadRoundTripPreservesTargetAndScript() throws Exception {
        ClusterEnvelope envelope = ClusterEnvelope.luaReload("node-1", "match", "return 1");

        ClusterEnvelope parsed = mapper.readValue(mapper.writeValueAsString(envelope), ClusterEnvelope.class);

        assertEquals(ClusterEnvelope.KIND_LUA_RELOAD, parsed.kind());
        assertEquals("match", parsed.luaTarget());
        assertEquals("return 1", parsed.luaScript());
    }

    @Test
    void kickFactoryCarriesKindAndUser() throws Exception {
        ClusterEnvelope parsed = mapper.readValue(
                mapper.writeValueAsString(ClusterEnvelope.kick("node-2", 99L)), ClusterEnvelope.class);

        assertEquals(ClusterEnvelope.KIND_KICK, parsed.kind());
        assertEquals("node-2", parsed.from());
        assertEquals(99L, parsed.userId());
    }

    /** 旧格式（无 luaTarget/luaScript）必须能反序列化 —— 滚动升级期间老节点发出的消息。 */
    @Test
    void legacyJsonWithoutLuaFieldsDeserializes() throws Exception {
        String legacy = """
                {"kind":"PUSH","from":"node-1","userId":5,"messageB64":null,"command":null}
                """;

        ClusterEnvelope parsed = mapper.readValue(legacy, ClusterEnvelope.class);

        assertEquals(ClusterEnvelope.KIND_PUSH, parsed.kind());
        assertEquals(5L, parsed.userId());
        assertNull(parsed.luaTarget());
        assertNull(parsed.luaScript());
    }

    /** 未知字段必须被忽略（否则新老节点字段不一致时会反序列化失败）。 */
    @Test
    void unknownFieldsAreIgnored() throws Exception {
        String withUnknown = """
                {"kind":"KICK","from":"node-1","userId":3,"futureField":"x","another":123}
                """;

        ClusterEnvelope parsed = mapper.readValue(withUnknown, ClusterEnvelope.class);

        assertEquals(ClusterEnvelope.KIND_KICK, parsed.kind());
        assertEquals(3L, parsed.userId());
        assertTrue(parsed.luaTarget() == null && parsed.command() == null);
    }
}
