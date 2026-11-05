package com.guitarvault;

import java.security.Principal;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.servlet.http.HttpServletResponse;

@Controller
@RequestMapping("/reports")
public class ReportController {
    private final ModerationService moderation;
    private final ContentReportRepository reports;
    private final InstrumentService instruments;
    public ReportController(ModerationService moderation, ContentReportRepository reports, InstrumentService instruments) {
        this.moderation = moderation; this.reports = reports; this.instruments = instruments;
    }
    @ModelAttribute public void privatePage(HttpServletResponse response) { response.setHeader("Cache-Control", "private, no-store"); }
    @GetMapping public String mine(Principal principal, @RequestParam(defaultValue = "0") int page, Model model) {
        if (page < 0 || page > 100000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        model.addAttribute("reportPage", reports.findByReporterIdOrderByIdDesc(instruments.currentUser(principal).getId(), PageRequest.of(page, 20)));
        return view(model, "report.mine", "reports");
    }
    @GetMapping("/new") public String form(Principal principal, @RequestParam ContentReport.TargetType type, @RequestParam Long targetId, Model model) {
        try { model.addAttribute("target", moderation.reportable(instruments.currentUser(principal), type, targetId)); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        model.addAttribute("targetType", type); model.addAttribute("targetId", targetId); model.addAttribute("reasons", ContentReport.Reason.values());
        return view(model, "report.create", "report-form");
    }
    @PostMapping public String create(Principal principal, @RequestParam ContentReport.TargetType type, @RequestParam Long targetId,
                                     @RequestParam(defaultValue = "") String reason, @RequestParam(defaultValue = "") String explanation,
                                     Model model, RedirectAttributes redirect) {
        try {
            ContentReport report = moderation.create(instruments.currentUser(principal), type, targetId, reason.strip(), explanation.strip());
            redirect.addFlashAttribute("success", "report.sent"); return "redirect:/reports/" + report.getId();
        } catch (IllegalArgumentException e) {
            if (e.getMessage().equals("report.selfError")) { redirect.addFlashAttribute("error", e.getMessage()); return "redirect:/reports"; }
            model.addAttribute("error", e.getMessage()); model.addAttribute("selectedReason", reason); model.addAttribute("explanation", explanation);
            return form(principal, type, targetId, model);
        }
    }
    @GetMapping("/{id}") public String detail(Principal principal, @PathVariable Long id, Model model) {
        model.addAttribute("report", reports.findByIdAndReporterId(id, instruments.currentUser(principal).getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return view(model, "report.detail", "report-detail");
    }
    private String view(Model model, String title, String template) { model.addAttribute("title", title); model.addAttribute("contentTemplate", template); return "layout"; }
}
