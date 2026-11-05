package com.guitarvault;

import java.util.List;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.servlet.http.HttpServletResponse;

@Controller
@RequestMapping("/admin/moderation")
public class ModerationController {
    private final ContentReportRepository reports;
    private final ModerationAuditRepository audit;
    private final ModerationService moderation;
    private final ReportTargetResolver targets;
    private final InstrumentService instruments;
    private final ProfilePhotoRepository profiles;
    private final StudioPhotoRepository studios;
    private final ConcertPosterRepository posters;
    private final ArticleCoverRepository covers;
    public ModerationController(ContentReportRepository reports, ModerationAuditRepository audit, ModerationService moderation,
                                ReportTargetResolver targets, InstrumentService instruments, ProfilePhotoRepository profiles,
                                StudioPhotoRepository studios, ConcertPosterRepository posters, ArticleCoverRepository covers) {
        this.reports = reports; this.audit = audit; this.moderation = moderation; this.targets = targets; this.instruments = instruments;
        this.profiles = profiles; this.studios = studios; this.posters = posters; this.covers = covers;
    }
    @ModelAttribute public void privatePage(HttpServletResponse response) { response.setHeader("Cache-Control", "private, no-store"); }
    private void requireAdmin(Authentication auth) { ModerationService.requireAdmin(auth); instruments.currentUser(auth); }
    @GetMapping public String index(Authentication auth, @RequestParam(defaultValue = "OPEN") String state,
                                    @RequestParam(defaultValue = "0") int page, Model model) {
        requireAdmin(auth);
        if (page < 0 || page > 100000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var request = PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "id"));
        if (state.equals("ALL")) model.addAttribute("reportPage", reports.findAll(request));
        else {
            try { model.addAttribute("reportPage", reports.findByStatusOrderByIdDesc(ContentReport.Status.valueOf(state), request)); }
            catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        }
        model.addAttribute("state", state); return view(model, "report.moderation", "moderation");
    }
    @GetMapping("/reports/{id}") public String detail(Authentication auth, @PathVariable Long id, Model model) {
        requireAdmin(auth); ContentReport report = reports.findById(id).orElseThrow(ModerationController::notFound);
        model.addAttribute("report", report); model.addAttribute("audit", audit.findByReportIdOrderByIdAsc(id));
        model.addAttribute("currentTarget", targets.find(report.getTargetType(), report.getTargetId(), false).orElse(null));
        model.addAttribute("superAdmin", ModerationService.superAdmin(auth)); model.addAttribute("mediaIds", mediaIds(report));
        return view(model, "report.detail", "moderation-detail");
    }
    @PostMapping("/reports/{id}/action") public String action(Authentication auth, @PathVariable Long id, @RequestParam String action,
                                                             @RequestParam(defaultValue = "") String note, RedirectAttributes redirect) {
        return act(auth, id, action, note, redirect);
    }
    @PostMapping("/reports/{id}/account/{action:suspend|reactivate}") public String account(Authentication auth, @PathVariable Long id,
                                                                                        @PathVariable String action, @RequestParam(defaultValue = "") String note, RedirectAttributes redirect) {
        return act(auth, id, action.toUpperCase(java.util.Locale.ROOT), note, redirect);
    }
    private String act(Authentication auth, Long id, String action, String note, RedirectAttributes redirect) {
        try { moderation.act(auth, id, action, note.strip()); redirect.addFlashAttribute("success", "report.updated"); }
        catch (IllegalArgumentException e) { redirect.addFlashAttribute("error", e.getMessage()); redirect.addFlashAttribute("moderatorNote", note); }
        return "redirect:/admin/moderation/reports/" + id;
    }
    private List<Long> mediaIds(ContentReport report) {
        Long id = report.getTargetId();
        return switch (report.getTargetType()) {
            case PROFILE -> profiles.existsByOwnerId(id) ? List.of(0L) : List.of();
            case STUDIO -> studios.findByStudioIdOrderByIdAsc(id).stream().map(StudioPhotoRepository.Summary::getId).toList();
            case CONCERT -> posters.existsByConcertId(id) ? List.of(0L) : List.of();
            case ARTICLE -> covers.existsById(id) ? List.of(0L) : List.of();
            default -> List.of();
        };
    }
    @GetMapping("/reports/{id}/media/{mediaId}") public ResponseEntity<byte[]> media(Authentication auth, @PathVariable Long id, @PathVariable Long mediaId) {
        requireAdmin(auth); ContentReport report = reports.findById(id).orElseThrow(ModerationController::notFound);
        if (!mediaIds(report).contains(mediaId)) throw notFound();
        Long targetId = report.getTargetId(); String type; byte[] data;
        switch (report.getTargetType()) {
            case PROFILE -> { var photo = profiles.findByOwnerId(targetId).orElseThrow(ModerationController::notFound); type = photo.getContentType(); data = photo.getData(); }
            case STUDIO -> { var photo = studios.findByIdAndStudioId(mediaId, targetId).orElseThrow(ModerationController::notFound); type = photo.getContentType(); data = photo.getData(); }
            case CONCERT -> { var photo = posters.findByConcertId(targetId).orElseThrow(ModerationController::notFound); type = photo.getContentType(); data = photo.getData(); }
            case ARTICLE -> { var photo = covers.findById(targetId).orElseThrow(ModerationController::notFound); type = photo.getContentType(); data = photo.getData(); }
            default -> throw notFound();
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff").body(data);
    }
    private String view(Model model, String title, String template) { model.addAttribute("title", title); model.addAttribute("contentTemplate", template); return "layout"; }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
