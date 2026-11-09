package com.guitarvault;

import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class DocumentLibraryController {
    private final DocumentLibraryService library;
    private final InstrumentService instruments;
    public DocumentLibraryController(DocumentLibraryService library, InstrumentService instruments) {
        this.library = library; this.instruments = instruments;
    }
    @GetMapping("/documents")
    public String index(Principal principal, @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) Long instrumentId, @RequestParam(required = false) InstrumentDocument.Kind category,
            @RequestParam(required = false) DocumentLibraryService.Access access,
            @RequestParam(defaultValue = "ACTIVE") DocumentLibraryService.State state,
            @RequestParam(defaultValue = "0") int page, Model model) {
        var filters = new DocumentLibraryService.Filter(q, instrumentId, category, access, state);
        var result = library.search(instruments.currentUser(principal), filters, page);
        model.addAttribute("documentPage", result.page());
        model.addAttribute("documents", result.page().getContent());
        model.addAttribute("instrumentChoices", result.instruments());
        model.addAttribute("filters", filters);
        model.addAttribute("categories", InstrumentDocument.Kind.values());
        model.addAttribute("accessLevels", DocumentLibraryService.Access.values());
        model.addAttribute("states", DocumentLibraryService.State.values());
        return page(model, "documents", "document.mine");
    }
    @GetMapping("/documents/{id}/manage")
    public String manage(Principal principal, @PathVariable Long id, Model model) {
        var document = library.detail(instruments.currentUser(principal), id);
        model.addAttribute("document", document);
        model.addAttribute("categories", InstrumentDocument.Kind.values());
        if (!model.containsAttribute("formTitle")) model.addAttribute("formTitle", document.title());
        if (!model.containsAttribute("formCategory")) model.addAttribute("formCategory", document.kind().name());
        if (!model.containsAttribute("formVersion")) model.addAttribute("formVersion", document.version());
        return page(model, "document-manage", "document.library.manage");
    }
    @PostMapping("/documents/{id}/labels")
    public String edit(Principal principal, @PathVariable Long id, @RequestParam long version,
            @RequestParam(defaultValue = "") String title, @RequestParam(defaultValue = "") String category,
            Model model, RedirectAttributes redirect) {
        try { library.edit(instruments.currentUser(principal), id, version, title, category); }
        catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage()); model.addAttribute("formTitle", title);
            model.addAttribute("formCategory", category); model.addAttribute("formVersion", version);
            return manage(principal, id, model);
        }
        redirect.addFlashAttribute("documentSuccess", "document.library.saved");
        return "redirect:/documents/" + id + "/manage";
    }
    @PostMapping("/documents/{id}/archive")
    public String archive(Principal principal, @PathVariable Long id, @RequestParam long version,
            @RequestParam boolean archived, RedirectAttributes redirect) {
        library.archive(instruments.currentUser(principal), id, version, archived);
        redirect.addFlashAttribute("documentSuccess", archived ? "document.library.archived" : "document.library.restored");
        return "redirect:/documents/" + id + "/manage";
    }
    @GetMapping("/documents/{id}/delete")
    public String confirmation(Principal principal, @PathVariable Long id, Model model) {
        model.addAttribute("document", library.deletable(instruments.currentUser(principal), id));
        return page(model, "document-delete", "document.library.delete");
    }
    @PostMapping("/documents/{id}/delete")
    public String delete(Principal principal, @PathVariable Long id, @RequestParam long version, RedirectAttributes redirect) {
        library.delete(instruments.currentUser(principal), id, version);
        redirect.addFlashAttribute("documentSuccess", "document.library.deleted");
        return "redirect:/documents";
    }
    @ExceptionHandler(ResponseStatusException.class)
    public String conflict(ResponseStatusException exception, Model model, jakarta.servlet.http.HttpServletResponse response) {
        if (exception.getStatusCode() != HttpStatus.CONFLICT) throw exception;
        response.setStatus(409); model.addAttribute("error", exception.getReason());
        return "document-conflict";
    }
    private static String page(Model model, String template, String title) {
        model.addAttribute("contentTemplate", template); model.addAttribute("title", title); return "layout";
    }
}
