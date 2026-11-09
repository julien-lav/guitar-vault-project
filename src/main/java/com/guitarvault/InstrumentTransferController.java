package com.guitarvault;

import java.security.Principal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class InstrumentTransferController {
    private final InstrumentService instruments;
    private final DocumentService documents;
    private final InstrumentTransferService service;
    private final InstrumentTransferRepository transfers;
    private final LuthierAssessmentRepository assessments;
    public InstrumentTransferController(InstrumentService instruments, InstrumentTransferService service,
            InstrumentTransferRepository transfers, LuthierAssessmentRepository assessments, DocumentService documents) {
        this.instruments = instruments; this.service = service; this.transfers = transfers; this.assessments = assessments; this.documents = documents;
    }
    @GetMapping("/transfers")
    public String inbox(Principal principal, Model model) {
        User actor = instruments.currentUser(principal);
        model.addAttribute("transfers", transfers.findBySenderIdOrRecipientIdOrderByIdDesc(actor.getId(), actor.getId()));
        return page(model, "transfers", "transfer.title");
    }
    @GetMapping("/instruments/{id}/transfers/new")
    public String form(Principal principal, @PathVariable Long id, Model model) {
        User actor = instruments.currentUser(principal);
        model.addAttribute("instrument", instruments.ownedInstrument(actor, id));
        model.addAttribute("selectableDocuments", documents.transferable(actor, id));
        if (!model.containsAttribute("selectedDocuments")) model.addAttribute("selectedDocuments", java.util.List.of());
        if (!model.containsAttribute("recipient")) model.addAttribute("recipient", "");
        return page(model, "transfer-form", "transfer.new");
    }
    @PostMapping("/instruments/{id}/transfers")
    public String propose(Principal principal, @PathVariable Long id, @RequestParam(defaultValue = "") String recipient,
            @RequestParam(defaultValue = "false") boolean confirmed, @RequestParam(required = false) java.util.List<Long> documentIds, Model model) {
        try { return "redirect:/transfers/" + service.propose(instruments.currentUser(principal), id, recipient, confirmed, documentIds).getId(); }
        catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage()); model.addAttribute("recipient", recipient);
            model.addAttribute("selectedDocuments", documentIds == null ? java.util.List.of() : documentIds);
            return form(principal, id, model);
        }
    }
    @GetMapping("/transfers/{id}")
    public String detail(Principal principal, @PathVariable Long id, Model model) {
        User actor = instruments.currentUser(principal);
        InstrumentTransfer transfer = service.visible(actor, id);
        model.addAttribute("transfer", transfer);
        model.addAttribute("isSender", transfer.getSender().getId().equals(actor.getId()));
        model.addAttribute("ownsInstrument", transfer.getInstrument().getOwner().getId().equals(actor.getId()));
        return page(model, "transfer-detail", "transfer.detail");
    }
    @PostMapping("/transfers/{id}/{action:accept|decline|cancel}")
    public String resolve(Principal principal, @PathVariable Long id, @PathVariable String action) {
        InstrumentTransfer.Status decision = switch (action) {
            case "accept" -> InstrumentTransfer.Status.ACCEPTED;
            case "decline" -> InstrumentTransfer.Status.DECLINED;
            default -> InstrumentTransfer.Status.CANCELLED;
        };
        service.resolve(instruments.currentUser(principal), id, decision);
        return "redirect:/transfers/" + id;
    }
    @ExceptionHandler(ResponseStatusException.class)
    public String conflict(ResponseStatusException exception, Model model, jakarta.servlet.http.HttpServletResponse response) {
        if (exception.getStatusCode() != HttpStatus.CONFLICT) throw exception;
        response.setStatus(409);
        model.addAttribute("error", exception.getReason());
        return "transfer-conflict";
    }
    public record Period(Long id, Instant startedAt, Instant endedAt, boolean yours) { }
    @GetMapping("/instruments/{id}/ownership")
    public String history(Principal principal, @PathVariable Long id, Model model) {
        User actor = instruments.currentUser(principal);
        model.addAttribute("periods", service.history(actor, id).stream()
                .map(p -> new Period(p.getId(), p.getStartedAt(), p.getEndedAt(), p.getOwner().getId().equals(actor.getId()))).toList());
        return page(model, "ownership-history", "transfer.history");
    }
    @GetMapping("/ownership/{id}/private")
    public String archive(Principal principal, @PathVariable Long id, Model model) {
        User actor = instruments.currentUser(principal);
        InstrumentOwnership period = service.archive(actor, id);
        model.addAttribute("period", period);
        model.addAttribute("photos", period.getInstrument().getPhotos().stream().filter(p -> p.visibleTo(actor)).toList());
        return page(model, "ownership-private", "transfer.privateArchive");
    }
    @GetMapping("/ownership/{id}/photos/{photoId}")
    public ResponseEntity<byte[]> archivePhoto(Principal principal, @PathVariable Long id, @PathVariable Long photoId) {
        User actor = instruments.currentUser(principal);
        InstrumentOwnership period = service.archive(actor, id);
        InstrumentPhoto photo = period.getInstrument().getPhotos().stream()
                .filter(p -> p.getId().equals(photoId) && p.visibleTo(actor)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.getContentType()))
                .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff").body(photo.getData());
    }
    @GetMapping("/instruments/{id}/passport-reports")
    public String passportReports(Principal principal, @PathVariable Long id, Model model) {
        model.addAttribute("instrument", instruments.ownedInstrument(instruments.currentUser(principal), id));
        var reports = assessments.findByInterventionInstrumentIdOrderByRecordedAtDesc(id);
        var latest = reports.stream().collect(java.util.stream.Collectors.toMap(
                r -> r.getIntervention().getId(), r -> r,
                (a, b) -> a.getRevision() > b.getRevision() ? a : b));
        model.addAttribute("currentReportIds", latest.values().stream().map(LuthierAssessment::getId).collect(java.util.stream.Collectors.toSet()));
        model.addAttribute("reports", reports);
        model.addAttribute("reportDocuments", documents.reportAttachments(instruments.currentUser(principal), reports));
        return page(model, "passport-reports", "transfer.reports");
    }
    private static String page(Model model, String template, String title) {
        model.addAttribute("title", title); model.addAttribute("contentTemplate", template); return "layout";
    }
}
