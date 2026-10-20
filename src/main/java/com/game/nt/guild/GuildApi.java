package com.game.nt.guild;

/** 公会模块对外能力（模块 API）。 */
public interface GuildApi {

    /** 玩家当前所在公会 ID；未加入返回 null。 */
    Long guildIdOf(long userId);

    /** 公会名（用于聊天频道展示等）；不存在返回 null。 */
    String guildName(long guildId);
}
