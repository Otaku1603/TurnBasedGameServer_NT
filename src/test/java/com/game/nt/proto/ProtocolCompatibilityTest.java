package com.game.nt.proto;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 协议兼容性金标测试（"字段号/枚举值只增不改"的机器校验）。
 *
 * <p>协议 v2 的兼容性规范靠人工遵守风险很高：一次误改字段号会静默破坏老客户端。
 * 本测试用生成的 {@link FileDescriptor} 锁定当前已发布的编号——
 * **新增字段/枚举值不会失败**（允许 additive），**改动或删除已发布编号会失败**。
 *
 * <p>若确需破坏性变更：升 {@code version} 并同步更新本测试中的期望值（这是刻意的双重确认）。
 */
class ProtocolCompatibilityTest {

    private static final FileDescriptor PROTO = GameProto.getDescriptor();

    // ---------- 断言辅助 ----------

    private static void assertFields(Descriptor message, Map<String, Integer> expected) {
        assertThat(message).as("message 必须存在").isNotNull();
        expected.forEach((name, number) -> {
            FieldDescriptor field = message.findFieldByName(name);
            assertThat(field).as("%s.%s 必须存在", message.getName(), name).isNotNull();
            assertThat(field.getNumber())
                    .as("%s.%s 字段号不得变更", message.getName(), name)
                    .isEqualTo(number);
        });
    }

    private static void assertEnumValues(EnumDescriptor descriptor, Map<String, Integer> expected) {
        assertThat(descriptor).as("enum 必须存在").isNotNull();
        expected.forEach((name, number) -> {
            EnumValueDescriptor value = descriptor.findValueByName(name);
            assertThat(value).as("%s.%s 必须存在", descriptor.getName(), name).isNotNull();
            assertThat(value.getNumber())
                    .as("%s.%s 编号不得变更", descriptor.getName(), name)
                    .isEqualTo(number);
        });
    }

    // ---------- 枚举编号 ----------

    @Test
    void messageTypeNumbersAreStable() {
        assertEnumValues(PROTO.findEnumTypeByName("MessageType"), Map.ofEntries(
                Map.entry("MESSAGE_TYPE_UNSPECIFIED", 0),
                Map.entry("HEARTBEAT", 1), Map.entry("ERROR", 2),
                Map.entry("LOGIN", 10), Map.entry("LOGIN_RESPONSE", 11),
                Map.entry("MATCH_REQUEST", 20), Map.entry("MATCH_CANCEL", 21), Map.entry("MATCH_SUCCESS", 22),
                Map.entry("BATTLE_READY", 30), Map.entry("BATTLE_START", 31), Map.entry("BATTLE_ACTION", 32),
                Map.entry("BATTLE_UPDATE", 33), Map.entry("BATTLE_END", 34),
                Map.entry("BATTLE_SURRENDER", 35), Map.entry("BATTLE_REJOIN", 36),
                Map.entry("BATTLE_REJOIN_RESPONSE", 37),
                Map.entry("MAIL_NOTIFY", 40), Map.entry("CHAT_PUSH", 41),
                Map.entry("ANNOUNCEMENT_PUSH", 42), Map.entry("FRIEND_REQUEST", 43)
        ));
    }

    @Test
    void actionAndReasonAndErrorCodesAreStable() {
        assertEnumValues(PROTO.findEnumTypeByName("ActionType"), Map.of(
                "ACTION_TYPE_UNSPECIFIED", 0, "USE_SKILL", 1, "DEFEND", 2, "USE_ITEM", 3));
        assertEnumValues(PROTO.findEnumTypeByName("EndReason"), Map.of(
                "END_REASON_UNSPECIFIED", 0, "DEFEAT", 1, "SURRENDER", 2,
                "TIMEOUT", 3, "DISCONNECT", 4, "OPPONENT_LEFT", 5));
        assertEnumValues(PROTO.findEnumTypeByName("ErrorCode"), Map.of(
                "ERROR_CODE_UNSPECIFIED", 0, "OK", 1, "UNAUTHORIZED", 2, "FORBIDDEN", 3,
                "INVALID_ARGUMENT", 4, "NOT_FOUND", 5, "CONFLICT", 6, "RATE_LIMITED", 7, "INTERNAL", 8));
    }

    // ---------- 关键字段号 ----------

    @Test
    void gameMessageFieldNumbersAreStable() {
        assertFields(PROTO.findMessageTypeByName("GameMessage"), Map.ofEntries(
                Map.entry("version", 1), Map.entry("requestId", 2), Map.entry("type", 3),
                Map.entry("traceId", 4), Map.entry("token", 5),
                Map.entry("loginRequest", 10), Map.entry("heartbeat", 11),
                Map.entry("matchRequest", 12), Map.entry("matchCancelRequest", 13),
                Map.entry("battleReadyRequest", 14), Map.entry("battleActionRequest", 15),
                Map.entry("battleSurrenderRequest", 16), Map.entry("battleRejoinRequest", 17),
                Map.entry("loginResponse", 30), Map.entry("errorResponse", 31),
                Map.entry("heartbeatResponse", 32), Map.entry("matchSuccessResponse", 33),
                Map.entry("battleStartResponse", 34), Map.entry("battleUpdateResponse", 35),
                Map.entry("battleEndResponse", 36), Map.entry("battleRejoinResponse", 37),
                Map.entry("mailNotify", 40), Map.entry("chatPush", 41),
                Map.entry("announcementPush", 42), Map.entry("friendRequestPush", 43)
        ));
    }

    @Test
    void battleMessagesFieldNumbersAreStable() {
        assertFields(PROTO.findMessageTypeByName("BattleActionRequest"), Map.of(
                "battleId", 1, "actionType", 2, "paramId", 3));
        assertFields(PROTO.findMessageTypeByName("BattlePlayerData"), Map.ofEntries(
                Map.entry("userId", 1), Map.entry("nickname", 2), Map.entry("maxHp", 3),
                Map.entry("currentHp", 4), Map.entry("attack", 5), Map.entry("defense", 6),
                Map.entry("isAlive", 7), Map.entry("cooldowns", 8), Map.entry("ownedSkillIds", 9)));
        assertFields(PROTO.findMessageTypeByName("BattleUpdateResponse"), Map.ofEntries(
                Map.entry("battleId", 1), Map.entry("currentRound", 2), Map.entry("actorUserId", 3),
                Map.entry("skillId", 4), Map.entry("skillName", 5), Map.entry("targetUserId", 6),
                Map.entry("damage", 7), Map.entry("heal", 8),
                Map.entry("player1", 10), Map.entry("player2", 11),
                Map.entry("nextActorUserId", 12), Map.entry("description", 13), Map.entry("actionType", 14)));
        assertFields(PROTO.findMessageTypeByName("BattleEndResponse"), Map.of(
                "battleId", 1, "winnerId", 2, "loserId", 3, "endReason", 4, "totalRounds", 5,
                "player1", 10, "player2", 11));
    }
}
