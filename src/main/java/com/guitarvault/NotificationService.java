package com.guitarvault;

import java.security.Principal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {
    private final NotificationRepository notifications;
    public NotificationService(NotificationRepository notifications) { this.notifications = notifications; }
    @Transactional(propagation = Propagation.MANDATORY)
    public void send(User actor, User recipient, Notification.Kind kind, String subject, String target) {
        if (!recipient.isEnabled() || recipient.getId().equals(actor.getId())) return;
        // Destinations are created by domain services; never accept an arbitrary redirect URL from a request.
        if (subject == null || subject.length() > 200 || !validTarget(target)) throw new IllegalArgumentException("Invalid notification");
        notifications.save(new Notification(recipient, kind, subject, target));
    }
    @Transactional(readOnly = true)
    public long unreadFor(Principal principal) {
        if (principal == null) return 0;
        return notifications.unreadForEmail(principal.getName());
    }
    @Transactional
    public void markRead(User actor, Long id) {
        if (notifications.markRead(id, actor.getId(), Instant.now()) == 0) throw notFound();
    }
    @Transactional
    public String open(User actor, Long id) {
        Notification notification = notifications.findByIdAndRecipientId(id, actor.getId()).orElseThrow(NotificationService::notFound);
        String target = notification.getTargetUrl();
        if (!validTarget(target)) throw notFound();
        notifications.markRead(id, actor.getId(), Instant.now());
        return target;
    }
    @Transactional
    public void markAllRead(User actor) { notifications.markAllRead(actor.getId(), Instant.now()); }
    private static boolean validTarget(String target) {
        return target != null && target.matches("/(?:bands/[1-9][0-9]*(?:#band-network)?|band-connections|concerts/[1-9][0-9]*|quotes/[1-9][0-9]*|date-proposals/[1-9][0-9]*|transfers/[1-9][0-9]*|appointments/[1-9][0-9]*|interventions/[1-9][0-9]*|shop-conversations/[1-9][0-9]*)");
    }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
