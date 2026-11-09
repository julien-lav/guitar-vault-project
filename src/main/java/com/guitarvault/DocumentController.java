package com.guitarvault;

import java.security.Principal;
import java.util.List;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class DocumentController {
    private final DocumentService documents;
    private final DocumentLibraryService library;
    private final InstrumentService instruments;
    public DocumentController(DocumentService documents, InstrumentService instruments, DocumentLibraryService library) {
        this.documents = documents; this.library = library; this.instruments = instruments;
    }
    @GetMapping("/instruments/{id}/documents")
    public String instrument(Principal principal, @PathVariable Long id, Model model) {
        User actor = instruments.currentUser(principal);
        model.addAttribute("instrument", instruments.ownedInstrument(actor, id));
        model.addAttribute("documents", library.forInstrument(actor, id));
        model.addAttribute("kinds", List.of(InstrumentDocument.Kind.INVOICE, InstrumentDocument.Kind.RECEIPT,
                InstrumentDocument.Kind.CERTIFICATE, InstrumentDocument.Kind.APPRAISAL, InstrumentDocument.Kind.OTHER));
        return page(model, "instrument-documents", "document.title");
    }
    @PostMapping("/instruments/{id}/documents")
    public String upload(Principal principal, @PathVariable Long id, @RequestParam(defaultValue = "") String title,
            @RequestParam(defaultValue = "OTHER") String kind, @RequestParam(required = false) MultipartFile file,
            Model model, RedirectAttributes redirect) {
        try { documents.upload(instruments.currentUser(principal), id, title, kind, file); }
        catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage()); model.addAttribute("documentTitle", title); model.addAttribute("documentKind", kind);
            return instrument(principal, id, model);
        }
        redirect.addFlashAttribute("documentSuccess", "document.uploaded");
        return "redirect:/instruments/" + id + "/documents";
    }
    @GetMapping("/assessments/{id}/attachments/new")
    public String attachmentForm(Principal principal, @PathVariable Long id, Model model) {
        model.addAttribute("report", documents.authoredReport(instruments.currentUser(principal), id));
        return page(model, "document-attachment-form", "document.attach");
    }
    @PostMapping("/assessments/{id}/attachments")
    public String attach(Principal principal, @PathVariable Long id, @RequestParam(defaultValue = "") String title,
            @RequestParam(defaultValue = "PRIVATE") String visibility, @RequestParam(required = false) MultipartFile file,
            Model model, RedirectAttributes redirect) {
        User actor = instruments.currentUser(principal);
        LuthierAssessment report = documents.authoredReport(actor, id);
        try { documents.attach(actor, id, title, visibility, file); }
        catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage()); model.addAttribute("documentTitle", title); model.addAttribute("documentVisibility", visibility);
            return attachmentForm(principal, id, model);
        }
        redirect.addFlashAttribute("documentSuccess", "document.uploaded");
        return "redirect:/interventions/" + report.getIntervention().getId();
    }
    @GetMapping("/documents/{id}/download")
    public ResponseEntity<byte[]> download(Principal principal, @PathVariable Long id) {
        User actor = instruments.currentUser(principal);
        InstrumentDocument document = documents.visible(actor, id);
        String extension = switch (document.getContentType()) { case "application/pdf" -> ".pdf"; case "image/png" -> ".png"; default -> ".jpg"; };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(document.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("document-" + id + extension).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .body(documents.bytes(actor, id));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String oversized(RedirectAttributes redirect) {
        redirect.addFlashAttribute("documentError", "document.fileError");
        return "redirect:/documents";
    }
    private static String page(Model model, String template, String title) {
        model.addAttribute("contentTemplate", template); model.addAttribute("title", title); return "layout";
    }
}
