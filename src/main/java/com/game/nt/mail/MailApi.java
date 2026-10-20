package com.game.nt.mail;

import java.time.Duration;
import java.util.List;

/**
 * 邮件模块对外能力（模块 API）。
 *
 * <p>其他模块（战斗结算、商城、后台、活动）通过本接口发系统邮件，
 * 不需要了解邮件的表结构与推送细节。
 */
public interface MailApi {

    /** 附件：金币或道具。 */
    record Attachment(String type, int itemId, int amount) {

        public static final String TYPE_GOLD = "GOLD";
        public static final String TYPE_ITEM = "ITEM";

        public static Attachment gold(int amount) {
            return new Attachment(TYPE_GOLD, 0, amount);
        }

        public static Attachment item(int itemId, int amount) {
            return new Attachment(TYPE_ITEM, itemId, amount);
        }
    }

    /** 发一封带附件的系统邮件，返回邮件 ID。 */
    long sendSystemMail(long receiverId, String title, String content,
                        List<Attachment> attachments, Duration ttl);

    /** 发一封无附件的系统邮件。 */
    default long sendSystemMail(long receiverId, String title, String content) {
        return sendSystemMail(receiverId, title, content, List.of(), Duration.ofDays(30));
    }

    /** 未读邮件数（收件箱红点）。 */
    int unreadCount(long receiverId);
}
