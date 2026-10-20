package com.game.nt.mail.internal;

import com.game.nt.shared.web.CurrentUserSupport;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 邮件 HTTP 接口。身份一律从 Authorization 头解析（不信任任何请求体里的 userId）。
 */
@RestController
@RequestMapping("/api/mail")
public class MailController {

    private final MailService mailService;
    private final CurrentUserSupport currentUser;

    public MailController(MailService mailService, CurrentUserSupport currentUser) {
        this.mailService = mailService;
        this.currentUser = currentUser;
    }

    @GetMapping("/list")
    public MailService.MailList list(@RequestHeader(value = "Authorization", required = false) String auth,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return mailService.listMails(currentUser.requireUserId(auth), page, size);
    }

    @GetMapping("/unread")
    public Map<String, Integer> unread(@RequestHeader(value = "Authorization", required = false) String auth) {
        return Map.of("unread", mailService.unreadCount(currentUser.requireUserId(auth)));
    }

    @PostMapping("/{id}/read")
    public MailService.MailDetail read(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @PathVariable("id") long mailId) {
        return mailService.readMail(currentUser.requireUserId(auth), mailId);
    }

    @PostMapping("/{id}/claim")
    public MailService.ClaimResult claim(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @PathVariable("id") long mailId) {
        return mailService.claim(currentUser.requireUserId(auth), mailId);
    }

    @PostMapping("/claim-all")
    public Map<String, List<Long>> claimAll(@RequestHeader(value = "Authorization", required = false) String auth) {
        return Map.of("claimedMailIds", mailService.claimAll(currentUser.requireUserId(auth)));
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @PathVariable("id") long mailId) {
        mailService.deleteMail(currentUser.requireUserId(auth), mailId);
        return Map.of("success", true);
    }
}
