/**
 * 战斗领域。
 *
 * <p>核心范式：<b>每场战斗一个单线程 Actor（mailbox）</b>。
 * 战斗状态（{@code BattleRoom} 内的可变对象）只允许在房间线程上读写，
 * 外部（Netty 处理线程、定时任务、匹配）只能通过 {@code submit(command)} 投递。
 * 这从根上消除了原项目"两个 worker 线程无锁共享 Battle 对象"的竞态。
 */
@ApplicationModule(displayName = "battle")
package com.game.nt.battle;

import org.springframework.modulith.ApplicationModule;
