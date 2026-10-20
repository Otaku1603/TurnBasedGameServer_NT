/**
 * 静态配置字典（gamedata）。
 *
 * <p>技能 / 道具 / 角色模板等"策划数值"的只读查询出口——客户端用它渲染技能名、道具图标与职业信息。
 * 与 {@code shop}（道具购买）不同：本模块只读配置表，不含任何交易逻辑。
 *
 * <p>依赖方向：gamedata 只依赖 shared（鉴权/错误码），不依赖任何业务模块。
 */
@ApplicationModule(displayName = "gamedata")
package com.game.nt.gamedata;

import org.springframework.modulith.ApplicationModule;
