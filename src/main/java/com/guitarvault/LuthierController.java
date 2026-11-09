package com.guitarvault;

import java.security.Principal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class LuthierController {
    private final LuthierService luthiers;
    private final DocumentService documents;
    private final InstrumentService instruments;
    private final LuthierProfileRepository profiles;
    private final LuthierInterventionRepository interventions;
    private final LuthierAssessmentRepository assessments;
    private final LuthierVerificationRepository verifications;
    private final AdminAccess admins;
    public LuthierController(LuthierService luthiers, InstrumentService instruments, LuthierProfileRepository profiles,
            LuthierInterventionRepository interventions, LuthierAssessmentRepository assessments,
            LuthierVerificationRepository verifications, AdminAccess admins, DocumentService documents) {
        this.luthiers = luthiers; this.documents = documents; this.instruments = instruments; this.profiles = profiles;
        this.interventions = interventions; this.assessments = assessments; this.verifications = verifications; this.admins = admins;
    }
    @GetMapping("/luthiers")
    public String directory(Model model) {
        model.addAttribute("luthiers", luthiers.directory());
        model.addAttribute("reviewMode", false);
        return page(model, "luthiers", "luthier.directory");
    }
    @GetMapping("/luthiers/{id}")
    public String profile(@PathVariable Long id, Model model) {
        model.addAttribute("luthier", luthiers.publicProfile(id));
        model.addAttribute("reviewMode", false);
        return page(model, "luthier-detail", "luthier.profile");
    }
    @GetMapping("/workshop")
    public String workshop(Principal principal, Model model) {
        User actor = instruments.currentUser(principal);
        LuthierProfile p = luthiers.ownProfile(actor);
        if (!model.containsAttribute("form")) {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("version", p == null ? "0" : Long.toString(p.getVersion()));
            if (p != null) {
                form.put("name", p.getName()); form.put("location", p.getLocation());
                form.put("specialties", p.getSpecialties()); form.put("description", p.getDescription());
                form.put("contact", p.getContact()); form.put("published", Boolean.toString(p.isPublished()));
            }
            model.addAttribute("form", form);
        }
        model.addAttribute("luthier", p);
        return page(model, "luthier-form", "luthier.workshop");
    }
    @PostMapping("/workshop")
    public String saveWorkshop(Principal principal, @RequestParam Map<String, String> params, Model model) {
        Map<String, String> form = clean(params, "name", "location", "specialties", "description", "contact", "published", "version");
        try { luthiers.saveProfile(instruments.currentUser(principal), form); }
        catch (IllegalArgumentException e) { error(model, form, e); return workshop(principal, model); }
        return "redirect:/workshop";
    }
    @GetMapping("/admin/luthiers")
    public String reviews(Principal principal, Model model) {
        admin(principal);
        model.addAttribute("luthiers", profiles.findAll());
        model.addAttribute("reviewMode", true);
        return page(model, "luthiers", "luthier.review");
    }
    @GetMapping("/admin/luthiers/{id}")
    public String review(Principal principal, @PathVariable Long id, Model model) {
        admin(principal);
        LuthierProfile p = profiles.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("luthier", p);
        model.addAttribute("reviewMode", true);
        model.addAttribute("reviews", verifications.findByProfileIdOrderByIdDesc(id));
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of("version", Long.toString(p.getVersion())));
        return page(model, "luthier-detail", "luthier.review");
    }
    @PostMapping("/admin/luthiers/{id}/verification")
    public String verify(Principal principal, @PathVariable Long id, @RequestParam Map<String, String> params, Model model) {
        User actor = admin(principal);
        Map<String, String> form = clean(params, "version", "verified", "evidence");
        try { luthiers.verify(actor, id, form); }
        catch (IllegalArgumentException e) { error(model, form, e); return review(principal, id, model); }
        return "redirect:/admin/luthiers/" + id;
    }
    @GetMapping("/interventions")
    public String inbox(Principal principal, Model model) {
        User actor = instruments.currentUser(principal);
        model.addAttribute("interventions", interventions.findByRequesterIdOrLuthierUserIdOrderByIdDesc(actor.getId(), actor.getId()));
        return page(model, "interventions", "luthier.interventions");
    }
    @GetMapping("/instruments/{id}/interventions/new")
    public String requestForm(Principal principal, @PathVariable Long id, Model model) {
        User actor = instruments.currentUser(principal);
        model.addAttribute("instrument", instruments.ownedInstrument(actor, id));
        model.addAttribute("luthiers", luthiers.directory().stream().filter(p -> !p.getUser().getId().equals(actor.getId())).toList());
        model.addAttribute("kinds", LuthierIntervention.Kind.values());
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of());
        return page(model, "intervention-form", "luthier.request");
    }
    @PostMapping("/instruments/{id}/interventions")
    public String request(Principal principal, @PathVariable Long id, @RequestParam Map<String, String> params, Model model) {
        Map<String, String> form = clean(params, "luthierId", "kind", "description");
        try {
            return "redirect:/interventions/" + luthiers.request(instruments.currentUser(principal), id, form).getId();
        } catch (IllegalArgumentException e) { error(model, form, e); return requestForm(principal, id, model); }
    }
    @GetMapping("/interventions/{id}")
    public String intervention(Principal principal, @PathVariable Long id, Model model) {
        User actor = instruments.currentUser(principal);
        LuthierIntervention i = luthiers.visible(actor, id);
        List<LuthierAssessment> history = assessments.findByInterventionIdOrderByRevisionDesc(id);
        model.addAttribute("intervention", i);
        model.addAttribute("assessments", history);
        model.addAttribute("reportDocuments", documents.reportAttachments(actor, history));
        model.addAttribute("isLuthier", i.getLuthier().getUser().getId().equals(actor.getId()));
        model.addAttribute("conclusions", i.getKind() == LuthierIntervention.Kind.AUTHENTICITY
                ? List.of(LuthierAssessment.Conclusion.CONSISTENT, LuthierAssessment.Conclusion.INCONCLUSIVE, LuthierAssessment.Conclusion.INCONSISTENT)
                : List.of(LuthierAssessment.Conclusion.values()));
        model.addAttribute("today", LocalDate.now(ZoneOffset.UTC));
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of("version", Integer.toString(history.size()), "examinedOn", LocalDate.now(ZoneOffset.UTC).toString()));
        return page(model, "intervention-detail", "luthier.intervention");
    }
    @PostMapping("/interventions/{id}/assessments")
    public String assess(Principal principal, @PathVariable Long id, @RequestParam Map<String, String> params, Model model) {
        Map<String, String> form = clean(params, "version", "examinedOn", "conclusion", "findings", "evidence", "limitations", "correctionReason");
        try { luthiers.assess(instruments.currentUser(principal), id, form); }
        catch (IllegalArgumentException e) { error(model, form, e); return intervention(principal, id, model); }
        return "redirect:/interventions/" + id;
    }
    @PostMapping("/interventions/{id}/cancel")
    public String cancel(Principal principal, @PathVariable Long id) {
        luthiers.cancel(instruments.currentUser(principal), id);
        return "redirect:/interventions/" + id;
    }
    private User admin(Principal principal) {
        User actor = instruments.currentUser(principal);
        if (!admins.allows(actor)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return actor;
    }
    private static String page(Model model, String template, String title) {
        model.addAttribute("title", title); model.addAttribute("contentTemplate", template); return "layout";
    }
    private static void error(Model model, Map<String, String> form, IllegalArgumentException e) {
        model.addAttribute("form", form); model.addAttribute("error", e.getMessage());
    }
    private static Map<String, String> clean(Map<String, String> params, String... fields) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String field : fields) result.put(field, params.getOrDefault(field, "").strip());
        return result;
    }
}
