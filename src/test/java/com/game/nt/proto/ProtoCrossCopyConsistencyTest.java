package com.game.nt.proto;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 回归：客户端 C# proto 生成物（Game.cs）与服务端 game.proto 的一致性校验。
 *
 * <p>两端协议是两份手工同步副本，任一端改协议漏同步即静默断线（字段号错位 = 解析出脏数据）。
 * 幸运的是 protoc 生成的 Game.cs 内嵌了完整的 FileDescriptor（base64），本测试直接解码它，
 * 与服务端运行时 {@link GameProto#getDescriptor()} 做全量结构比对：
 * <ul>
 *   <li>全部消息（含嵌套）的字段：名称 → 字段号 + 类型；</li>
 *   <li>全部枚举：名称 → 成员 → 编号。</li>
 * </ul>
 * 只比对结构关键信息（不比对 options/protoc 版本元数据，避免误报）。
 *
 * <p>客户端仓库不在工作区时本测试跳过 —— <b>跳过≠通过</b>，CI 必须在双仓齐全的环境执行；
 * 修改任一端协议后本测试必须双端同步并全绿。
 */
class ProtoCrossCopyConsistencyTest {

    private static final Path CLIENT_GAME_CS =
            Path.of("../TurnBasedGameClient_NT/Assets/Plugins/Proto/Gen/Game.cs");

    @Test
    void clientGeneratedDescriptorMatchesServerProto() throws Exception {
        assumeTrue(Files.exists(CLIENT_GAME_CS),
                "客户端仓库不在工作区（" + CLIENT_GAME_CS.toAbsolutePath() + "），跳过协议一致性校验；"
                        + "注意：跳过≠通过，改协议必须在双仓齐全环境执行本测试");

        FileDescriptorProto server = GameProto.getDescriptor().toProto();
        FileDescriptorProto client = extractEmbeddedDescriptor(CLIENT_GAME_CS);

        List<String> diffs = new ArrayList<>();
        compareEnums(server, client, diffs);
        compareMessages(server, client, diffs);
        assertTrue(diffs.isEmpty(),
                "客户端 Game.cs 与服务端 game.proto 漂移（协议双副本必须同步）:\n  - "
                        + String.join("\n  - ", diffs));
    }

    // ---------- 结构比对 ----------

    private static void compareEnums(FileDescriptorProto server, FileDescriptorProto client, List<String> diffs) {
        Map<String, Map<String, Integer>> serverEnums = enumTable(server);
        Map<String, Map<String, Integer>> clientEnums = enumTable(client);
        for (var entry : serverEnums.entrySet()) {
            Map<String, Integer> expected = entry.getValue();
            Map<String, Integer> actual = clientEnums.get(entry.getKey());
            if (actual == null) {
                diffs.add("枚举 " + entry.getKey() + " 在客户端 Game.cs 缺失");
                continue;
            }
            assertEquals(expected, actual, "枚举 " + entry.getKey() + " 成员编号漂移");
        }
        clientEnums.keySet().stream()
                .filter(name -> !serverEnums.containsKey(name))
                .forEach(name -> diffs.add("枚举 " + name + " 在服务端 game.proto 缺失"));
    }

    private static void compareMessages(FileDescriptorProto server, FileDescriptorProto client, List<String> diffs) {
        Map<String, Map<String, String>> serverMsgs = messageTable(server);
        Map<String, Map<String, String>> clientMsgs = messageTable(client);
        for (var entry : serverMsgs.entrySet()) {
            Map<String, String> expected = entry.getValue();
            Map<String, String> actual = clientMsgs.get(entry.getKey());
            if (actual == null) {
                diffs.add("消息 " + entry.getKey() + " 在客户端 Game.cs 缺失");
                continue;
            }
            assertEquals(expected, actual, "消息 " + entry.getKey() + " 字段号/类型漂移");
        }
        clientMsgs.keySet().stream()
                .filter(name -> !serverMsgs.containsKey(name))
                .forEach(name -> diffs.add("消息 " + name + " 在服务端 game.proto 缺失"));
    }

    /** 枚举表：枚举名 → (成员名 → 编号)，含嵌套枚举（当前 proto 无，防御性覆盖）。 */
    private static Map<String, Map<String, Integer>> enumTable(FileDescriptorProto file) {
        Map<String, Map<String, Integer>> table = new LinkedHashMap<>();
        for (EnumDescriptorProto e : file.getEnumTypeList()) {
            table.put(e.getName(), members(e));
        }
        for (DescriptorProto m : file.getMessageTypeList()) {
            collectNestedEnums(m, m.getName(), table);
        }
        return table;
    }

    private static void collectNestedEnums(DescriptorProto msg, String prefix,
                                           Map<String, Map<String, Integer>> table) {
        for (EnumDescriptorProto e : msg.getEnumTypeList()) {
            table.put(prefix + "." + e.getName(), members(e));
        }
        for (DescriptorProto nested : msg.getNestedTypeList()) {
            collectNestedEnums(nested, prefix + "." + nested.getName(), table);
        }
    }

    private static Map<String, Integer> members(EnumDescriptorProto e) {
        Map<String, Integer> values = new LinkedHashMap<>();
        e.getValueList().forEach(v -> values.put(v.getName(), v.getNumber()));
        return values;
    }

    /** 消息表：消息全名 → (字段名 → "类型#字段号")，含嵌套消息（map entry 会自然出现且两端一致）。 */
    private static Map<String, Map<String, String>> messageTable(FileDescriptorProto file) {
        Map<String, Map<String, String>> table = new LinkedHashMap<>();
        for (DescriptorProto m : file.getMessageTypeList()) {
            collectMessages(m, m.getName(), table);
        }
        return table;
    }

    private static void collectMessages(DescriptorProto msg, String prefix,
                                        Map<String, Map<String, String>> table) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldDescriptorProto f : msg.getFieldList()) {
            String type = f.getTypeName().isEmpty() ? f.getType().name() : f.getTypeName();
            fields.put(f.getName(), type + "#" + f.getNumber());
        }
        table.put(prefix, fields);
        for (DescriptorProto nested : msg.getNestedTypeList()) {
            collectMessages(nested, prefix + "." + nested.getName(), table);
        }
    }

    // ---------- Game.cs 内嵌 descriptor 提取 ----------

    private static final Pattern CONCAT_BLOCK = Pattern.compile(
            "FromBase64String\\(\\s*string\\.Concat\\((.*?)\\)", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    /**
     * 提取 Game.cs 静态构造器里 string.Concat(...) 拼接的 base64 并解码为 FileDescriptorProto。
     * 这段 base64 就是生成该文件所用 game.proto 的完整结构（protoc 写死在生成物里）。
     */
    static FileDescriptorProto extractEmbeddedDescriptor(Path gameCs) throws Exception {
        String text = Files.readString(gameCs);
        Matcher block = CONCAT_BLOCK.matcher(text);
        assertFalse(!block.find(), "Game.cs 中未找到内嵌 descriptor（生成物结构可能已变化）");
        StringBuilder b64 = new StringBuilder();
        Matcher literals = STRING_LITERAL.matcher(block.group(1));
        while (literals.find()) {
            b64.append(literals.group(1));
        }
        assertFalse(b64.isEmpty(), "Game.cs 内嵌 descriptor 为空");
        return FileDescriptorProto.parseFrom(Base64.getMimeDecoder().decode(b64.toString()));
    }
}
