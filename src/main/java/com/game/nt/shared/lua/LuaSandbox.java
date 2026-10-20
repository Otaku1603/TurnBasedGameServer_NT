package com.game.nt.shared.lua;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import party.iroiro.luajava.Lua;
import party.iroiro.luajava.LuaException;
import party.iroiro.luajava.luaj.LuaJ;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Lua 公式沙箱。
 *
 * <p>与原项目的差异（安全）：
 * <ol>
 *   <li>不再使用 {@code standardGlobals()} 之类的"全开"环境，加载后显式剥离
 *       {@code os / io / package / debug / dofile / loadfile / require / luajava / java / load}
 *       等危险全局，杜绝脚本执行系统命令或反射 Java 类（原项目可 RCE）。</li>
 *   <li>所有调用在单一实例上 {@code synchronized}，规避 Lua 虚拟机非线程安全导致的未定义行为。</li>
 *   <li>脚本加载失败或执行异常时，由调用方降级到 Java 兜底公式，不影响可用性。</li>
 * </ol>
 *
 * <p>注意：本实现选择纯 Java 后端 {@link LuaJ}（无原生库），避免 JNI 原生库被杀软拦截；
 * 若后续追求性能，可替换为 {@code lua54-platform} 后端，本类对外接口不变。
 */
@Component
public class LuaSandbox {

    private static final Logger log = LoggerFactory.getLogger(LuaSandbox.class);

    /** 加载后必须置 nil 的危险全局名 */
    private static final List<String> FORBIDDEN_GLOBALS = List.of(
            "os", "io", "package", "debug",
            "dofile", "loadfile", "require", "load",
            "luajava", "java", "javax"
    );

    private final String scriptPath;
    private final Object lock = new Object();

    private Lua lua;
    private volatile boolean ready = false;

    public LuaSandbox(@Value("${app.lua.script-path:lua/damage_formulas.lua}") String scriptPath) {
        this.scriptPath = scriptPath;
    }

    @PostConstruct
    public void init() {
        synchronized (lock) {
            try {
                lua = new LuaJ();
                lua.openLibraries();
                stripDangerousGlobals();

                String script = readScript(scriptPath);
                lua.run(script);

                ready = true;
                log.info("Lua 沙箱初始化成功，脚本={}，已剥离危险全局={}", scriptPath, FORBIDDEN_GLOBALS);
            } catch (Exception ex) {
                ready = false;
                // 不阻断启动：Lua 不可用时业务自动走 Java 兜底
                log.error("Lua 沙箱初始化失败，将使用 Java 兜底公式。原因: {}", ex.getMessage(), ex);
            }
        }
    }

    @PreDestroy
    public void destroy() {
        synchronized (lock) {
            if (lua != null) {
                try {
                    lua.close();
                } catch (Exception ignored) {
                    // 关闭失败可忽略
                }
                lua = null;
            }
        }
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * 调用 Lua 函数并返回整数结果。
     *
     * @param functionName 已加载脚本中的函数名
     * @param args         按顺序传入的参数（Long / Double / Boolean / String）
     * @return 整数结果
     * @throws LuaException 函数不存在或执行异常（由调用方兜底）
     */
    public long callForLong(String functionName, Object... args) {
        synchronized (lock) {
            if (!ready) {
                throw new IllegalStateException("Lua 沙箱未就绪");
            }
            lua.getGlobal(functionName);
            if (lua.isNil(-1)) {
                lua.pop(1);
                throw new IllegalStateException("Lua 函数不存在: " + functionName);
            }
            pushArgs(args);
            lua.pCall(args.length, 1);
            int top = lua.getTop();
            long result = lua.toInteger(top);
            lua.pop(1);
            return result;
        }
    }

    public long callForLong(String functionName, Map<String, Object> ctx) {
        // 便捷重载：不常用，直接按固定顺序取值
        throw new UnsupportedOperationException("请使用 callForLong(String, Object...)");
    }

    /**
     * 热更新：重新加载脚本（新脚本语法错误时保持旧版本可用）。
     *
     * @param newScript 新脚本内容；null/空白 = 从 classpath 按 {@code scriptPath} 重新加载
     */
    public boolean reload(String newScript) {
        synchronized (lock) {
            if (lua == null) {
                return false;
            }
            try {
                String script = (newScript == null || newScript.isBlank())
                        ? readScript(scriptPath) : newScript;
                lua.run(script);
                log.info("Lua 脚本热更新成功，脚本={}，长度={}", scriptPath, script.length());
                return true;
            } catch (Exception ex) {
                log.error("Lua 脚本热更新失败，保留旧版本。原因: {}", ex.getMessage());
                return false;
            }
        }
    }

    private void pushArgs(Object... args) {
        for (Object arg : args) {
            switch (arg) {
                case null -> lua.pushNil();
                case Boolean b -> lua.push(b.booleanValue());
                case Integer i -> lua.push(i.longValue());
                case Long l -> lua.push(l.longValue());
                case Double d -> lua.push((Number) d);
                case Float f -> lua.push((Number) f.doubleValue());
                case String s -> lua.push(s);
                default -> lua.push(String.valueOf(arg));
            }
        }
    }

    private void stripDangerousGlobals() {
        for (String name : FORBIDDEN_GLOBALS) {
            lua.pushNil();
            lua.setGlobal(name);
        }
    }

    private String readScript(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IOException("Lua 脚本不存在: " + path);
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
