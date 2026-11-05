package com.guitarvault;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ConcertController {
    private final ConcertService service;
    private final BandService bandService;
    private final BandRepository bands;
    private final ConcertParticipationRepository participations;
    private final ConcertPosterRepository posters;
    private final InstrumentService instruments;
    public ConcertController(ConcertService service, BandService bandService, BandRepository bands,
                             ConcertParticipationRepository participations, ConcertPosterRepository posters, InstrumentService instruments) {
        this.service = service; this.bandService = bandService; this.bands = bands;
        this.participations = participations; this.posters = posters; this.instruments = instruments;
    }
    @GetMapping("/bands/{bandId}/concerts/new")
    public String createForm(Principal principal, @PathVariable Long bandId, Model model) {
        return form(model, bandService.editable(instruments.currentUser(principal), bandId), null);
    }
    @PostMapping("/bands/{bandId}/concerts")
    public String create(Principal principal, @PathVariable Long bandId, @RequestParam Map<String, String> params,
                         @RequestParam(required = false) MultipartFile poster, Model model, RedirectAttributes redirect) {
        return save(principal, bandId, null, params, poster, false, model, redirect);
    }
    @GetMapping("/concerts/{id}")
    public String detail(Principal principal, @PathVariable Long id, Model model) {
        User actor = instruments.currentUser(principal);
        Concert concert = service.find(id);
        ContentVisibility.require(!concert.isModerationHidden());
        boolean manager = concert.getOrganizer().getCreator().getId().equals(actor.getId());
        var confirmed = participations.findByConcertIdAndStatusOrderByBandNameAsc(id, ConcertParticipation.Status.ACCEPTED).stream().filter(p -> !p.getBand().isModerationHidden()).toList();
        var pending = manager ? participations.findByConcertIdAndStatusOrderByBandNameAsc(id, ConcertParticipation.Status.PENDING) : List.<ConcertParticipation>of();
        model.addAttribute("concert", concert);
        model.addAttribute("canManageConcert", manager);
        model.addAttribute("actorId", actor.getId());
        model.addAttribute("confirmedParticipants", confirmed);
        model.addAttribute("pendingParticipants", pending);
        model.addAttribute("myConcertInvitations", concert.isCancelled() ? List.of() : participations.findByConcertIdAndBandCreatorIdAndStatus(id, actor.getId(), ConcertParticipation.Status.PENDING));
        var occupied = new java.util.HashSet<Long>();
        occupied.add(concert.getOrganizer().getId());
        confirmed.forEach(p -> occupied.add(p.getBand().getId())); pending.forEach(p -> occupied.add(p.getBand().getId()));
        model.addAttribute("inviteBands", manager && !concert.isCancelled() ? bands.findAllByOrderByIdDesc().stream()
                .filter(b -> b.getCreator().isEnabled() && !occupied.contains(b.getId())).toList() : List.of());
        model.addAttribute("hasPoster", posters.existsByConcertId(id));
        model.addAttribute("pageTitle", concert.getTitle() + " — Guitar Vault");
        model.addAttribute("contentTemplate", "concert-detail"); return "layout";
    }
    @GetMapping("/concerts/{id}/edit")
    public String editForm(Principal principal, @PathVariable Long id, Model model) {
        Concert concert = service.editable(instruments.currentUser(principal), id);
        if (concert.isCancelled()) throw new ResponseStatusException(HttpStatus.CONFLICT);
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of("title", concert.getTitle(), "date", concert.getDate().toString(),
                "venue", concert.getVenue(), "city", concert.getCity(), "description", concert.getDescription()));
        return form(model, concert.getOrganizer(), id);
    }
    @PostMapping("/concerts/{id}")
    public String edit(Principal principal, @PathVariable Long id, @RequestParam Map<String, String> params,
                       @RequestParam(required = false) MultipartFile poster, @RequestParam(defaultValue = "false") boolean removePoster,
                       Model model, RedirectAttributes redirect) {
        return save(principal, null, id, params, poster, removePoster, model, redirect);
    }
    private String save(Principal principal, Long bandId, Long id, Map<String, String> params, MultipartFile poster,
                        boolean removePoster, Model model, RedirectAttributes redirect) {
        User actor = instruments.currentUser(principal);
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : List.of("title", "date", "venue", "city", "description")) values.put(field, params.getOrDefault(field, "").strip());
        try {
            Concert concert = service.save(actor, bandId, id, values, poster, removePoster);
            redirect.addFlashAttribute("success", "concert.saved"); return "redirect:/concerts/" + concert.getId();
        } catch (IllegalArgumentException e) {
            model.addAttribute("form", values); model.addAttribute("removePoster", removePoster); model.addAttribute("error", e.getMessage());
            return id == null ? createForm(principal, bandId, model) : editForm(principal, id, model);
        }
    }
    private String form(Model model, Band band, Long id) {
        model.addAttribute("band", band); model.addAttribute("concertId", id);
        model.addAttribute("hasPoster", id != null && posters.existsByConcertId(id));
        model.addAttribute("title", id == null ? "concert.create" : "concert.edit"); model.addAttribute("contentTemplate", "concert-form");
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of());
        return "layout";
    }
    @GetMapping("/concerts/{id}/poster")
    public ResponseEntity<byte[]> poster(Principal principal, @PathVariable Long id) {
        instruments.currentUser(principal); ContentVisibility.require(!service.find(id).isModerationHidden());
        ConcertPoster poster = posters.findByConcertId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(poster.getContentType()))
                .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff").body(poster.getData());
    }
    @PostMapping("/concerts/{id}/invitations")
    public String invite(Principal principal, @PathVariable Long id, @RequestParam Long bandId, RedirectAttributes redirect) {
        try { service.invite(instruments.currentUser(principal), id, bandId); redirect.addFlashAttribute("success", "concert.invited"); }
        catch (IllegalArgumentException e) { redirect.addFlashAttribute("error", e.getMessage()); }
        return "redirect:/concerts/" + id;
    }
    @GetMapping("/concert-invitations")
    public String invitations(Principal principal, Model model) {
        model.addAttribute("concertInvitations", participations.findByBandCreatorIdAndStatusAndConcertCancelledFalseOrderByIdDesc(
                instruments.currentUser(principal).getId(), ConcertParticipation.Status.PENDING));
        model.addAttribute("title", "concert.invitations"); model.addAttribute("contentTemplate", "concert-invitations"); return "layout";
    }
    @PostMapping("/concert-participations/{id}/{action:accept|decline|remove}")
    public String respond(Principal principal, @PathVariable Long id, @PathVariable String action, RedirectAttributes redirect) {
        try {
            Long concertId = service.respond(instruments.currentUser(principal), id, action);
            redirect.addFlashAttribute("success", "concert.done." + action); return "redirect:/concerts/" + concertId;
        } catch (IllegalArgumentException e) { redirect.addFlashAttribute("error", e.getMessage()); return "redirect:/concert-invitations"; }
    }
    @GetMapping("/concert-participations/{id}/remove")
    public String removeForm(Principal principal, @PathVariable Long id, Model model) {
        ConcertParticipation p = service.removable(instruments.currentUser(principal), id);
        if (p.getConcert().isCancelled() || (p.getStatus() != ConcertParticipation.Status.PENDING && p.getStatus() != ConcertParticipation.Status.ACCEPTED)) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return confirmation(model, p.getConcert(), "/concert-participations/" + id + "/remove", "concert.remove", "concert.removePrompt", p.getBand().getName());
    }
    @GetMapping("/concerts/{id}/cancel")
    public String cancelForm(Principal principal, @PathVariable Long id, Model model) {
        Concert concert = service.editable(instruments.currentUser(principal), id);
        if (concert.isCancelled()) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return confirmation(model, concert, "/concerts/" + id + "/cancel", "concert.cancel", "concert.cancelPrompt", concert.getTitle());
    }
    @PostMapping("/concerts/{id}/cancel")
    public String cancel(Principal principal, @PathVariable Long id, RedirectAttributes redirect) {
        service.cancel(instruments.currentUser(principal), id); redirect.addFlashAttribute("success", "concert.cancelledMessage"); return "redirect:/concerts/" + id;
    }
    private String confirmation(Model model, Concert concert, String action, String title, String prompt, String target) {
        model.addAttribute("concert", concert); model.addAttribute("action", action); model.addAttribute("title", title);
        model.addAttribute("prompt", prompt); model.addAttribute("target", target); model.addAttribute("contentTemplate", "concert-confirm"); return "layout";
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooLarge(HttpServletRequest request, RedirectAttributes redirect) {
        redirect.addFlashAttribute("error", "concert.posterError");
        String path = request.getRequestURI();
        if (path.matches("/bands/[0-9]+/concerts")) return "redirect:" + path + "/new";
        if (path.matches("/concerts/[0-9]+")) return "redirect:" + path + "/edit";
        return "redirect:/bands";
    }
}
