package com.game.nt.mail.internal;

import com.game.nt.economy.EconomyApi;
import com.game.nt.shared.push.ClientPushApi;
import com.game.nt.identity.IdentityApi;
import com.game.nt.mail.MailApi;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.MailNotify;
import com.game.nt.proto.GameProto.MessageType;
import com.game.nt.shared.error.BusinessException;
import com.game.nt.shared.error.ErrorCode;
import com.game.nt.shared.idempotency.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 邮件服务。
 *
 * <p><b>业务原子化要点</b>（这是邮件系统最容易出资产事故的地方）：
 * <ol>
 *   <li>领取走<b>幂等键</b>（{@code mail:claim:{userId}:{mailId}}），重复请求直接拒绝</li>
 *   <li>每个附件用 <b>条件 UPDATE</b>（{@code WHERE claimed = 0}）抢占，返回 1 才发放 —— 并发下不会重复发</li>
 *   <li>整个领取在一个事务里；失败则幂等键随事务回滚，允许重试</li>
 *   <li>发放走 identity / economy 的原子 SQL，不做"读-改-写"</li>
 * </ol>
 */
@Service
public class MailService implements MailApi {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final int MAX_PAGE_SIZE = 50;

    private final MailMapper mailMapper;
    private final MailAttachmentMapper attachmentMapper;
    private final IdentityApi identityApi;
    private final EconomyApi economyApi;
    private final IdempotencyService idempotencyService;
    private final ClientPushApi clientPushApi;

    public MailService(MailMapper mailMapper,
                       MailAttachmentMapper attachmentMapper,
                       IdentityApi identityApi,
                       EconomyApi economyApi,
                       IdempotencyService idempotencyService,
                       ClientPushApi clientPushApi) {
        this.mailMapper = mailMapper;
        this.attachmentMapper = attachmentMapper;
        this.identityApi = identityApi;
        this.economyApi = economyApi;
        this.idempotencyService = idempotencyService;
        this.clientPushApi = clientPushApi;
    }

    // ==================== 对外 DTO ====================

    public record MailSummary(long id, String senderName, String title, boolean read,
                              boolean hasUnclaimed, LocalDateTime expireAt, LocalDateTime createdAt) {
    }

    public record AttachmentView(String type, int itemId, int amount) {
    }

    public record MailDetail(long id, String senderName, String title, String content, boolean read,
                             LocalDateTime expireAt, LocalDateTime createdAt, List<AttachmentView> attachments) {
    }

    public record ClaimResult(long gold, Map<Integer, Integer> items) {
    }

    public record MailList(int total, int unread, int page, int size, List<MailSummary> items) {
    }

    // ==================== MailApi 实现 ====================

    @Override
    @Transactional
    public long sendSystemMail(long receiverId, String title, String content,
                               List<Attachment> attachments, Duration ttl) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "邮件标题不能为空");
        }
        // 收件人必须存在，避免产生孤儿邮件
        identityApi.findByUserId(receiverId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "收件人不存在"));

        MailEntity mail = new MailEntity();
        mail.setReceiverId(receiverId);
        mail.setSenderType(MailEntity.SENDER_SYSTEM);
        mail.setSenderName("系统");
        mail.setTitle(title);
        mail.setContent(content == null ? "" : content);
        mail.setIsRead(0);
        mail.setIsDeleted(0);
        mail.setExpireAt(LocalDateTime.now().plus(ttl == null ? Duration.ofDays(30) : ttl));
        mailMapper.insert(mail);

        if (attachments != null) {
            for (Attachment attachment : attachments) {
                if (attachment.amount() <= 0) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "附件数量必须为正");
                }
                MailAttachmentEntity entity = new MailAttachmentEntity();
                entity.setMailId(mail.getId());
                entity.setType(attachment.type());
                entity.setItemId(attachment.itemId());
                entity.setAmount(attachment.amount());
                entity.setClaimed(0);
                attachmentMapper.insert(entity);
            }
        }

        pushUnreadNotify(receiverId);
        log.info("系统邮件已发送: mailId={}, receiver={}, 附件数={}", mail.getId(), receiverId,
                attachments == null ? 0 : attachments.size());
        return mail.getId();
    }

    @Override
    public int unreadCount(long receiverId) {
        return mailMapper.countUnread(receiverId);
    }

    // ==================== 玩家侧操作 ====================

    public MailList listMails(long userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        List<MailEntity> mails = mailMapper.pageByReceiver(userId, safeSize, safePage * safeSize);

        List<MailSummary> items = new ArrayList<>(mails.size());
        for (MailEntity mail : mails) {
            items.add(new MailSummary(
                    mail.getId(), mail.getSenderName(), mail.getTitle(),
                    mail.getIsRead() != null && mail.getIsRead() == 1,
                    attachmentMapper.countUnclaimed(mail.getId()) > 0,
                    mail.getExpireAt(), mail.getCreatedAt()));
        }
        int unread = mailMapper.countUnread(userId);
        // total 用未删除总数近似（分页展示够用；精确统计可另加 countByReceiver）
        return new MailList(items.size(), unread, safePage, safeSize, items);
    }

    @Transactional
    public MailDetail readMail(long userId, long mailId) {
        MailEntity mail = requireOwnedMail(userId, mailId);
        mailMapper.markRead(mailId, userId);
        List<AttachmentView> attachments = attachmentMapper.listByMail(mailId).stream()
                .map(a -> new AttachmentView(a.getType(), a.getItemId(), a.getAmount()))
                .toList();
        return new MailDetail(mail.getId(), mail.getSenderName(), mail.getTitle(), mail.getContent(),
                true, mail.getExpireAt(), mail.getCreatedAt(), attachments);
    }

    /** 领取单封邮件的全部附件（幂等）。 */
    @Transactional
    public ClaimResult claim(long userId, long mailId) {
        MailEntity mail = requireOwnedMail(userId, mailId);
        if (mail.getExpireAt() != null && mail.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.CONFLICT, "邮件已过期，无法领取");
        }

        String idemKey = "mail:claim:" + userId + ":" + mailId;
        if (!idempotencyService.tryAcquire(idemKey)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该邮件附件已领取，请勿重复操作");
        }

        return grantAttachments(userId, mailId, idemKey);
    }

    /** 一键领取所有可领邮件。 */
    @Transactional
    public List<Long> claimAll(long userId) {
        List<Long> claimedMailIds = new ArrayList<>();
        for (MailEntity mail : mailMapper.listClaimable(userId)) {
            String idemKey = "mail:claim:" + userId + ":" + mail.getId();
            if (!idempotencyService.tryAcquire(idemKey)) {
                continue; // 已被领取，跳过
            }
            grantAttachments(userId, mail.getId(), idemKey);
            claimedMailIds.add(mail.getId());
        }
        return claimedMailIds;
    }

    @Transactional
    public void deleteMail(long userId, long mailId) {
        MailEntity mail = requireOwnedMail(userId, mailId);
        if (attachmentMapper.countUnclaimed(mailId) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "邮件内还有未领取的附件，不能删除");
        }
        mailMapper.softDelete(mail.getId(), userId);
    }

    // ==================== 内部 ====================

    private ClaimResult grantAttachments(long userId, long mailId, String idemKey) {
        long gold = 0;
        Map<Integer, Integer> items = new LinkedHashMap<>();

        for (MailAttachmentEntity attachment : attachmentMapper.listByMail(mailId)) {
            if (attachment.getClaimed() != null && attachment.getClaimed() == 1) {
                continue;
            }
            // 抢占：返回 1 才由本次发放，杜绝并发重复发奖
            if (attachmentMapper.markClaimed(attachment.getId()) != 1) {
                continue;
            }
            if (MailAttachmentEntity.TYPE_GOLD.equals(attachment.getType())) {
                identityApi.addGold(userId, attachment.getAmount());
                gold += attachment.getAmount();
            } else {
                economyApi.grantItem(userId, attachment.getItemId(), attachment.getAmount());
                items.merge(attachment.getItemId(), attachment.getAmount(), Integer::sum);
            }
        }

        idempotencyService.markDone(idemKey, "gold=" + gold + ",items=" + items);
        log.info("邮件附件领取完成: userId={}, mailId={}, gold={}, items={}", userId, mailId, gold, items);
        return new ClaimResult(gold, items);
    }

    private MailEntity requireOwnedMail(long userId, long mailId) {
        MailEntity mail = mailMapper.selectById(mailId);
        if (mail == null || mail.getIsDeleted() != null && mail.getIsDeleted() == 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "邮件不存在");
        }
        if (!Long.valueOf(userId).equals(mail.getReceiverId())) {
            // 越权访问他人邮件
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该邮件");
        }
        return mail;
    }

    private void pushUnreadNotify(long receiverId) {
        try {
            int unread = mailMapper.countUnread(receiverId);
            clientPushApi.sendToUser(receiverId, GameMessage.newBuilder()
                    .setType(MessageType.MAIL_NOTIFY)
                    .setMailNotify(MailNotify.newBuilder().setUnreadCount(unread).build())
                    .build());
        } catch (Exception ex) {
            // 推送失败不影响发信本身
            log.warn("邮件提醒推送失败: receiver={}, err={}", receiverId, ex.getMessage());
        }
    }

    /**
     * 每日清理过期邮件。
     * 注意：多实例部署时需加分布式锁（路线图 A9），否则会重复执行（幂等，影响可控）。
     */
    @Scheduled(cron = "${app.mail.cleanup-cron:0 0 4 * * *}")
    public void cleanupExpired() {
        int deleted = mailMapper.deleteExpired();
        if (deleted > 0) {
            log.info("已清理过期邮件 {} 封", deleted);
        }
    }
}
