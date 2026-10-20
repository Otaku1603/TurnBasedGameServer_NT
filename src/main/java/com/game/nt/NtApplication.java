package com.game.nt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TurnBasedGame_Server_NT 启动类。
 *
 * <p>架构定位：<b>模块化单体（Modular Monolith）</b>。
 * 一级子包即领域模块，模块间只允许通过 {@code api} 包中的接口 + 领域事件通信，
 * 由 Spring Modulith 负责边界校验（见 {@code src/test} 中的架构测试）。
 *
 * <p>模块划分：
 * <ul>
 *   <li>{@code gateway}   —— Netty 长连接接入层（会话、协议编解码、消息路由）</li>
 *   <li>{@code identity}  —— 认证与会话身份</li>
 *   <li>{@code character} —— 角色与属性</li>
 *   <li>{@code battle}    —— 战斗领域（房间 Actor / 命令 / 事件 / 状态机）</li>
 *   <li>{@code match}     —— 匹配</li>
 *   <li>{@code economy}   —— 经济系统（金币 / 商城 / 背包）</li>
 *   <li>{@code social}    —— 社交（好友 / 留言 / 榜单）</li>
 *   <li>{@code admin}     —— 后台管理</li>
 *   <li>{@code shared}    —— 共享内核（错误码、幂等、Lua、可观测等），对所有模块开放</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
public class NtApplication {

    public static void main(String[] args) {
        SpringApplication.run(NtApplication.class, args);
    }
}
