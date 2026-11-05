package com.guitarvault;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModerationService {
    private final ContentReportRepository reports;
    private final ModerationAuditRepository audit;
    private final ReportTargetResolver targets;
    private final UserRepository users;
    private final AdminAccess admins;
    private final SessionRegistry sessions;
    public ModerationService(ContentReportRepository reports, ModerationAuditRepository audit, ReportTargetResolver targets,
                             UserRepository users, AdminAccess admins, SessionRegistry sessions) {
        this.reports = reports; this.audit = audit; this.targets = targets; this.users = users; this.admins = admins; this.sessions = sessions;
    }
    public static boolean superAdmin(Authentication auth) { return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(Role.ROLE_SUPER_ADMIN.name())); }
    public static void requireAdmin(Authentication auth) { if (!ArticlePermissions.canPublish(auth)) throw new AccessDeniedException("Moderation is restricted to administrators"); }
    public ReportTargetResolver.Target reportable(User actor, ContentReport.TargetType type, Long id) {
        var target = targets.find(type, id, false).orElseThrow(ModerationService::notFound);
        ContentVisibility.require(!target.hidden() && target.publicContent());
        if (target.owner().getId().equals(actor.getId())) throw new IllegalArgumentException("report.selfError");
        return target;
    }
    @Transactional
    public ContentReport create(User actor, ContentReport.TargetType type, Long id, String reasonValue, String explanation) {
        var target = targets.find(type, id, true).orElseThrow(ModerationService::notFound);
        ContentVisibility.require(!target.hidden() && target.publicContent());
        if (target.owner().getId().equals(actor.getId())) throw new IllegalArgumentException("report.selfError");
        ContentReport.Reason reason;
        try { reason = ContentReport.Reason.valueOf(reasonValue); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("report.reasonError"); }
        if (explanation.length() > 2000 || (reason == ContentReport.Reason.OTHER && explanation.isBlank())) throw new IllegalArgumentException("report.explanationError");
        if (reports.existsByReporterIdAndTargetTypeAndTargetIdAndStatusNot(actor.getId(), type, id, ContentReport.Status.CLOSED)) throw new IllegalArgumentException("report.duplicateError");
        return reports.save(new ContentReport(actor, target, type, id, reason, explanation));
    }
    @Transactional
    public void act(Authentication auth, Long reportId, String actionValue, String note) {
        requireAdmin(auth);
        User actor = users.findByEmailIgnoreCase(auth.getName()).filter(User::isEnabled).orElseThrow(ModerationService::notFound);
        ModerationAudit.Action action;
        try { action = ModerationAudit.Action.valueOf(actionValue); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("report.actionError"); }
        if ((action == ModerationAudit.Action.SUSPEND || action == ModerationAudit.Action.REACTIVATE) && !superAdmin(auth)) throw new AccessDeniedException("Super administrator required");
        if (note.isBlank() || note.length() > 2000) throw new IllegalArgumentException("report.noteError");
        ContentReport report = reports.findForUpdate(reportId).orElseThrow(ModerationService::notFound);
        switch (action) {
            case REVIEW -> { if (report.getStatus() != ContentReport.Status.OPEN) throw new IllegalArgumentException("report.closedError"); report.review(); }
            case DISMISS -> { if (report.getStatus() == ContentReport.Status.CLOSED) throw new IllegalArgumentException("report.closedError"); report.close(ContentReport.Decision.DISMISSED); }
            case HIDE, RESTORE -> {
                var target = targets.find(report.getTargetType(), report.getTargetId(), true).orElseThrow(() -> new IllegalArgumentException("report.unavailable"));
                boolean hide = action == ModerationAudit.Action.HIDE;
                if (target.hidden() == hide) throw new IllegalArgumentException("report.unchangedError");
                targets.hidden(target, hide); report.close(hide ? ContentReport.Decision.HIDDEN : ContentReport.Decision.RESTORED);
            }
            case SUSPEND, REACTIVATE -> {
                User owner = users.findForUpdate(report.getOwner().getId()).orElseThrow(ModerationService::notFound);
                if (owner.getId().equals(actor.getId()) || admins.roles(owner).contains(Role.ROLE_SUPER_ADMIN)) throw new IllegalArgumentException("report.protectedAccount");
                boolean suspend = action == ModerationAudit.Action.SUSPEND;
                if (owner.isSuspended() == suspend) throw new IllegalArgumentException("report.unchangedError");
                owner.setSuspended(suspend); users.save(owner);
                report.close(suspend ? ContentReport.Decision.ACCOUNT_SUSPENDED : ContentReport.Decision.ACCOUNT_REACTIVATED);
                if (suspend) expireAfterCommit(owner.getEmail());
            }
        }
        reports.save(report); audit.save(new ModerationAudit(report, actor, action, note));
    }
    private void expireAfterCommit(String email) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                for (Object principal : sessions.getAllPrincipals()) {
                    if (principal instanceof org.springframework.security.core.userdetails.UserDetails user && user.getUsername().equalsIgnoreCase(email)) {
                        sessions.getAllSessions(principal, false).forEach(info -> info.expireNow());
                    }
                }
            }
        });
    }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
