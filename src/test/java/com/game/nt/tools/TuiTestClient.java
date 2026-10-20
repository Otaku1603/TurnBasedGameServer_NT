package com.game.nt.tools;

import com.game.nt.proto.GameProto;
import com.game.nt.proto.GameProto.ActionType;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.Heartbeat;
import com.game.nt.proto.GameProto.MessageType;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TUI 测试客户端（由旧项目 fwork/test/BattleSystemTestClient 移植到 NT 协议）。
 *
 * <p>协议适配：WebSocket + Protobuf v2 ——
 * <ul>
 *   <li>传输为 WS 二进制帧（每帧一个 GameMessage），默认走 Nginx 入口 :80/ws</li>
 *   <li>信封 version=2；token 仅 LOGIN 携带；请求体不再携带 userId（身份取自服务端会话）</li>
 *   <li>登录先走 HTTP /api/auth/login 获取 JWT，再在 WS 上发 LOGIN 绑定会话</li>
 * </ul>
 *
 * <p>推送实时打印（WS 回调线程直接输出，对齐旧客户端行为；与输入行交叠属正常现象）。
 * Windows 控制台请先 <code>chcp 65001</code>（tools\tui.cmd 已内置），本类强制 UTF-8 输出。
 *
 * <p>用法（tools\tui.cmd 已封装）：
 * <pre>
 * tools\tui.cmd [host] [wsPort] [httpPort]
 * </pre>
 */
public class TuiTestClient {

    private static String host = "10.244.216.57";
    private static int wsPort = 80;
    private static int httpPort = 80;

    private static final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
    private static java.util.concurrent.ScheduledFuture<?> matchWaitTask;
    private static long matchRequestedAt;
    private static final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "tui-heartbeat"));

    private static final java.util.concurrent.CountDownLatch loginDone =
            new java.util.concurrent.CountDownLatch(1);
    private static long userId;
    private static String battleId;
    private static boolean loggedIn;
    private static boolean inBattle;

    public static void main(String[] args) throws Exception {
        // 强制 UTF-8 输出：chcp 65001 的控制台下避免中文乱码（默认码页是 GBK）
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));

        if (args.length > 0 && !args[0].isBlank()) {
            host = args[0].trim();
        }
        if (args.length > 1 && !args[1].isBlank()) {
            wsPort = Integer.parseInt(args[1].trim());
        }
        if (args.length > 2 && !args[2].isBlank()) {
            httpPort = Integer.parseInt(args[2].trim());
        }

        Scanner scanner = new Scanner(System.in);
        System.out.println("========================================");
        System.out.println("TurnBasedGame NT 测试客户端 (WS + Protobuf v2)");
        System.out.println("服务器: ws://" + host + ":" + wsPort + "/ws   API: http://" + host + ":" + httpPort);
        System.out.println("管理后台: http://" + host + "/admin/login (admin/admin123)");
        System.out.println("========================================");
        System.out.print("用户名 [player1]: ");
        String username = scanner.nextLine().trim();
        if (username.isEmpty()) {
            username = "player1";
        }
        System.out.print("密码 [" + username + "pass]: ");
        String password = scanner.nextLine().trim();
        if (password.isEmpty()) {
            password = username + "pass";
        }

        String token = httpLogin(username, password);
        System.out.println("✓ HTTP 登录成功，已获取 JWT");

        connect(token);

        while (true) {
            printMenu();
            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1" -> sendMatch();
                case "2" -> sendReady();
                case "3" -> sendAction(scanner);
                case "4" -> sendSurrender();
                case "5" -> sendRejoin();
                case "6" -> {
                    WebSocket ws = wsRef.get();
                    if (ws != null) {
                        try {
                            ws.sendClose(WebSocket.NORMAL_CLOSURE, "simulate").join();
                        } catch (Exception ignored) {
                            // 对端可能已断开
                        }
                    }
                    System.out.println("⚠ 已主动断开（服务端标记掉线；战斗中可用 7 恢复盘面）");
                }
                case "7" -> {
                    connect(token);
                    sendRejoin();
                }
                case "0" -> {
                    WebSocket ws = wsRef.get();
                    if (ws != null) {
                        try {
                            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
                        } catch (Exception ignored) {
                            // 对端可能已断开
                        }
                    }
                    heartbeat.shutdownNow();
                    System.out.println("客户端退出");
                    return;
                }
                default -> System.out.println("无效指令");
            }
        }
    }

    private static void printMenu() {
        System.out.println("\n========== 菜单（推送会实时打印，可能与本行交叠） ==========");
        System.out.println("状态: " + (loggedIn ? "已登录" : "未登录")
                + (inBattle ? "（战斗中）" : "")
                + (battleId != null ? " | BattleID: " + battleId : ""));
        System.out.println("1. 请求匹配");
        System.out.println("2. 战斗准备");
        System.out.println("3. 战斗行动 (技能/防御/道具)");
        System.out.println("4. 投降");
        System.out.println("5. 断线重连（自动恢复盘面）");
        System.out.println("6. 模拟断网");
        System.out.println("7. 重连并恢复");
        System.out.println("0. 退出");
        System.out.print("> ");
    }

    // ==================== 连接 ====================

    private static void connect(String token) {
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                try {
                    // 推送实时打印：撮合/回合/结算不因等待输入而"卡住"
                    System.out.println(handle(GameMessage.parseFrom(data)));
                } catch (Exception ex) {
                    System.out.println("✗ 消息解析失败: " + ex.getMessage());
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                loggedIn = false;
                inBattle = false;
                System.out.println("⚠ 连接关闭: " + statusCode + " " + reason);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                // 主动 sendClose 后 JDK 会触发本地关闭错误，属正常流程
                if (!String.valueOf(error.getMessage()).contains("closed locally")) {
                    System.out.println("✗ 连接错误: " + error.getMessage());
                }
            }
        };

        WebSocket ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://" + host + ":" + wsPort + "/ws"), listener)
                .join();
        wsRef.set(ws);
        ws.request(1);
        System.out.println("✓ 已连接 ws://" + host + ":" + wsPort + "/ws");

        // 应用层心跳（服务端读空闲 30s 断开）
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                send(GameMessage.newBuilder().setVersion(2).setType(MessageType.HEARTBEAT)
                        .setHeartbeat(Heartbeat.newBuilder().setClientTime(System.currentTimeMillis()).build())
                        .build());
            } catch (Exception ignored) {
                // 断线静默
            }
        }, 10, 10, TimeUnit.SECONDS);

        // WS 上的会话登录（token 绑定身份），同步等确认避免竞态
        send(GameMessage.newBuilder().setVersion(2).setType(MessageType.LOGIN)
                .setToken(token).build());
        try {
            if (!loginDone.await(5, TimeUnit.SECONDS)) {
                System.out.println("✗ 会话登录超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 发送 ====================

    private static void send(GameMessage msg) {
        WebSocket ws = wsRef.get();
        if (ws == null) {
            System.out.println("✗ 未连接");
            return;
        }
        ws.sendBinary(ByteBuffer.wrap(msg.toByteArray()), true);
    }

    private static void sendMatch() {
        if (!loggedIn) {
            System.out.println("✗ 会话未就绪");
            return;
        }
        send(GameMessage.newBuilder().setVersion(2).setType(MessageType.MATCH_REQUEST).build());
        System.out.println("→ 已请求匹配（需双方都入队；Elo 分差大时区间会自动扩大，最长约 60s）");
        matchRequestedAt = System.currentTimeMillis();
        if (matchWaitTask != null) {
            matchWaitTask.cancel(false);
        }
        matchWaitTask = heartbeat.scheduleAtFixedRate(() -> {
            if (battleId != null) {
                matchWaitTask.cancel(false);
                return;
            }
            System.out.println("⏳ 匹配中… 已等待 "
                    + (System.currentTimeMillis() - matchRequestedAt) / 1000 + "s");
        }, 5, 5, TimeUnit.SECONDS);
    }

    private static void sendReady() {
        if (battleId == null) {
            System.out.println("✗ 尚未匹配");
            return;
        }
        send(GameMessage.newBuilder().setVersion(2).setType(MessageType.BATTLE_READY)
                .setBattleReadyRequest(GameProto.BattleReadyRequest.newBuilder()
                        .setBattleId(battleId).build())
                .build());
        System.out.println("→ 已发送战斗准备");
    }

    private static void sendAction(Scanner scanner) {
        if (!inBattle) {
            System.out.println("✗ 战斗未开始");
            return;
        }
        System.out.println("1. 技能（paramId=技能ID）  2. 防御  3. 道具（药水回血，需背包有货，商店可购）");
        System.out.print("选择 > ");
        String c = scanner.nextLine().trim();
        ActionType at;
        int paramId = 0;
        switch (c) {
            case "1" -> {
                at = ActionType.USE_SKILL;
                System.out.print("技能ID > ");
                paramId = Integer.parseInt(scanner.nextLine().trim());
            }
            case "2" -> at = ActionType.DEFEND;
            case "3" -> {
                at = ActionType.USE_ITEM;
                System.out.print("道具ID > ");
                paramId = Integer.parseInt(scanner.nextLine().trim());
            }
            default -> {
                System.out.println("无效选择");
                return;
            }
        }
        send(GameMessage.newBuilder().setVersion(2).setType(MessageType.BATTLE_ACTION)
                .setBattleActionRequest(GameProto.BattleActionRequest.newBuilder()
                        .setBattleId(battleId).setActionType(at).setParamId(paramId).build())
                .build());
        System.out.println("→ 已发送动作: " + at);
    }

    private static void sendSurrender() {
        if (battleId == null) {
            return;
        }
        send(GameMessage.newBuilder().setVersion(2).setType(MessageType.BATTLE_SURRENDER)
                .setBattleSurrenderRequest(GameProto.BattleSurrenderRequest.newBuilder()
                        .setBattleId(battleId).build())
                .build());
        System.out.println("→ 已投降");
    }

    private static void sendRejoin() {
        send(GameMessage.newBuilder().setVersion(2).setType(MessageType.BATTLE_REJOIN)
                .setBattleRejoinRequest(GameProto.BattleRejoinRequest.newBuilder().build())
                .build());
        System.out.println("→ 已请求恢复盘面");
    }

    // ==================== 消息处理 ====================

    private static String handle(GameMessage msg) {
        return switch (msg.getType()) {
            case LOGIN_RESPONSE -> {
                var r = msg.getLoginResponse();
                if (r.getSuccess()) {
                    userId = r.getUserId();
                    loggedIn = true;
                    loginDone.countDown();
                    yield "✓ 会话登录成功 userId=" + userId;
                }
                yield "✗ 会话登录失败: " + r.getMessage();
            }
            case MATCH_SUCCESS -> {
                if (matchWaitTask != null) {
                    matchWaitTask.cancel(false);
                }
                var r = msg.getMatchSuccessResponse();
                battleId = r.getBattleId();
                yield "★ 匹配成功! 对手: " + r.getOpponent().getNickname()
                        + "（battleId=" + battleId + "）—— 按 2 准备";
            }
            case BATTLE_START -> {
                inBattle = true;
                var r = msg.getBattleStartResponse();
                yield "⚔ 战斗开始! 先手 userId=" + r.getCurrentActorUserId()
                        + (r.getCurrentActorUserId() == userId ? " —— 轮到你了（按 3 行动）" : " —— 等待对手");
            }
            case BATTLE_UPDATE -> {
                var r = msg.getBattleUpdateResponse();
                yield "\n>>> 回合 " + r.getCurrentRound() + " <<<\n"
                        + r.getDescription() + "\n"
                        + "P1 " + r.getPlayer1().getCurrentHp() + "/" + r.getPlayer1().getMaxHp()
                        + "  |  P2 " + r.getPlayer2().getCurrentHp() + "/" + r.getPlayer2().getMaxHp()
                        + (r.getNextActorUserId() == userId ? "\n👉 轮到你了（按 3 行动）" : "\n⏳ 等待对手行动...");
            }
            case BATTLE_END -> {
                inBattle = false;
                battleId = null;
                var r = msg.getBattleEndResponse();
                yield "🏁 战斗结束 | 原因: " + r.getEndReason() + " | 胜者 userId=" + r.getWinnerId();
            }
            case BATTLE_REJOIN_RESPONSE -> {
                var r = msg.getBattleRejoinResponse();
                if (r.getSuccess()) {
                    battleId = r.getBattleId();
                    inBattle = true;
                    yield "✓ 盘面已恢复 battleId=" + battleId;
                }
                yield "✗ 恢复失败: " + r.getMessage();
            }
            case HEARTBEAT -> null;
            default -> "收到消息: " + msg.getType();
        };
    }

    private static String httpLogin(String username, String password) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + httpPort + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP 登录失败: " + resp.statusCode() + " " + resp.body());
        }
        String body = resp.body();
        int i = body.indexOf("\"token\":\"");
        return body.substring(i + 9, body.indexOf('"', i + 9));
    }
}
