package com.guitarvault;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LuthierService {
    private final WorkshopJobRepository jobs;
    private final LuthierProfileRepository profiles;
    private final LuthierInterventionRepository interventions;
    private final LuthierAssessmentRepository assessments;
    private final LuthierVerificationRepository verifications;
    private final AdminAccess admins;
    private final InstrumentRepository instrumentRecords;
    public LuthierService(LuthierProfileRepository profiles, LuthierInterventionRepository interventions,
            LuthierAssessmentRepository assessments, LuthierVerificationRepository verifications,
            AdminAccess admins, InstrumentRepository instrumentRecords, WorkshopJobRepository jobs) {
        this.jobs = jobs;
        this.profiles = profiles; this.interventions = interventions; this.assessments = assessments;
        this.verifications = verifications; this.admins = admins; this.instrumentRecords = instrumentRecords;
    }
    public List<LuthierProfile> directory() {
        return profiles.findByPublishedTrueAndUserEnabledTrueAndUserSuspendedFalseAndUserModerationHiddenFalseOrderByNameAsc();
    }
    public LuthierProfile publicProfile(Long id) {
        return profiles.findById(id).filter(this::available).orElseThrow(LuthierService::notFound);
    }
    private boolean available(LuthierProfile p) {
        return p.isPublished() && p.getUser().isEnabled() && !p.getUser().isModerationHidden();
    }
    public LuthierProfile ownProfile(User actor) {
        return profiles.findByUserId(actor.getId()).orElse(null);
    }
    @Transactional
    public void saveProfile(User actor, Map<String, String> form) {
        required(form, "name", 150); required(form, "location", 200); required(form, "specialties", 500);
        required(form, "description", 3000); required(form, "contact", 300);
        LuthierProfile previous = ownProfile(actor);
        LuthierProfile p = previous == null ? new LuthierProfile(actor)
                : profiles.findForUpdate(previous.getId()).orElseThrow(LuthierService::notFound);
        if (p.getVersion() != version(form)) throw conflict();
        p.update(form.get("name"), form.get("location"), form.get("specialties"), form.get("description"),
                form.get("contact"), "true".equals(form.get("published")));
        profiles.saveAndFlush(p);
    }
    @Transactional
    public void verify(User admin, Long id, Map<String, String> form) {
        if (!admins.allows(admin)) throw notFound();
        required(form, "evidence", 2000);
        if (!List.of("true", "false").contains(form.get("verified"))) throw invalid();
        LuthierProfile p = profiles.findForUpdate(id).orElseThrow(LuthierService::notFound);
        if (p.getUser().getId().equals(admin.getId())) throw invalid();
        if (p.getVersion() != version(form)) throw conflict();
        boolean verified = "true".equals(form.get("verified"));
        verifications.save(new LuthierVerification(p, admin, verified, form.get("evidence")));
        p.verify(verified);
        profiles.saveAndFlush(p);
    }
    @Transactional
    public LuthierIntervention request(User actor, Long instrumentId, Map<String, String> form) {
        Instrument instrument = instrumentRecords.findForUpdate(instrumentId).orElseThrow(LuthierService::notFound);
        if (!instrument.getOwner().getId().equals(actor.getId())) throw notFound();
        required(form, "description", 3000);
        Long luthierId;
        LuthierIntervention.Kind kind;
        try { luthierId = Long.valueOf(form.get("luthierId")); kind = LuthierIntervention.Kind.valueOf(form.get("kind")); }
        catch (RuntimeException e) { throw invalid(); }
        LuthierProfile p = publicProfile(luthierId);
        if (p.getUser().getId().equals(actor.getId())) throw invalid();
        return interventions.save(new LuthierIntervention(instrument, actor, p, kind, form.get("description")));
    }
    public LuthierIntervention visible(User actor, Long id) {
        LuthierIntervention i = interventions.findById(id).orElseThrow(LuthierService::notFound);
        authorize(actor, i); return i;
    }
    private void authorize(User actor, LuthierIntervention i) {
        if (!i.getRequester().getId().equals(actor.getId()) && !i.getLuthier().getUser().getId().equals(actor.getId())) throw notFound();
    }
    @Transactional
    public void cancel(User actor, Long id) {
        LuthierIntervention i = interventions.findForUpdate(id).orElseThrow(LuthierService::notFound);
        authorize(actor, i);
        if (i.getStatus() != LuthierIntervention.Status.REQUESTED || jobs.findByInterventionId(id).isPresent()) throw conflict();
        i.cancel();
    }
    @Transactional
    public void assess(User actor, Long id, Map<String, String> form) {
        LuthierIntervention i = interventions.findForUpdate(id).orElseThrow(LuthierService::notFound);
        // Assignment grants access only to this request, never to the owner's whole collection.
        if (!i.getLuthier().getUser().getId().equals(actor.getId())) throw notFound();
        if (i.getStatus() == LuthierIntervention.Status.CANCELLED) throw conflict();
        List<LuthierAssessment> history = assessments.findByInterventionIdOrderByRevisionDesc(id);
        if (version(form) != history.size()) throw conflict();
        required(form, "findings", 5000); required(form, "evidence", 3000); required(form, "limitations", 3000);
        if (!history.isEmpty()) required(form, "correctionReason", 1000);
        String reason = history.isEmpty() ? "" : form.get("correctionReason");
        LocalDate examined;
        LuthierAssessment.Conclusion conclusion;
        try {
            String value = form.get("examinedOn");
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
            examined = LocalDate.parse(value);
            if (examined.getYear() < 1 || examined.isAfter(LocalDate.now(ZoneOffset.UTC))) throw invalid();
            conclusion = LuthierAssessment.Conclusion.valueOf(form.get("conclusion"));
        } catch (RuntimeException e) { throw invalid(); }
        if (i.getKind() == LuthierIntervention.Kind.AUTHENTICITY && conclusion == LuthierAssessment.Conclusion.WORK_RECORDED) throw invalid();
        assessments.save(new LuthierAssessment(i, actor, history.size() + 1, examined, conclusion,
                form.get("findings"), form.get("evidence"), form.get("limitations"), reason));
        i.complete();
    }
    private static void required(Map<String, String> form, String key, int max) {
        String value = form.get(key);
        if (value == null || value.isBlank() || value.length() > max) throw invalid();
    }
    private static long version(Map<String, String> form) {
        try { return Long.parseLong(Objects.requireNonNull(form.get("version"))); }
        catch (RuntimeException e) { throw conflict(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("luthier.invalid"); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
