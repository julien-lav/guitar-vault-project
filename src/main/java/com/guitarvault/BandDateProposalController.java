package com.guitarvault;

import java.security.Principal;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class BandDateProposalController {
    private final BandDateProposalService service;
    private final BandDateProposalRepository proposals;
    private final BandDateRevisionRepository revisions;
    private final InstrumentService instruments;
    private final BandService bands;
    public BandDateProposalController(BandDateProposalService service, BandDateProposalRepository proposals,
                                      BandDateRevisionRepository revisions, InstrumentService instruments, BandService bands) {
        this.service = service; this.proposals = proposals; this.revisions = revisions; this.instruments = instruments; this.bands = bands;
    }
    @ModelAttribute public void privatePage(HttpServletResponse response) { response.setHeader("Cache-Control", "private, no-store"); }
    @GetMapping("/date-proposals")
    public String index(Principal principal, @RequestParam(defaultValue = "0") int page, Model model) {
        if (page < 0 || page > 100000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        User actor = instruments.currentUser(principal);
        model.addAttribute("proposalPage", proposals.forManager(actor.getId(), PageRequest.of(page, 20)));
        model.addAttribute("actorId", actor.getId());
        return view(model, "proposal.title", "date-proposals");
    }
    @GetMapping("/bands/{id}/date-proposals/new")
    public String form(Principal principal, @PathVariable Long id, @RequestParam(required = false) Long senderId, Model model) {
        var senders = service.eligibleSenders(instruments.currentUser(principal), id);
        if (senders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        model.addAttribute("targetBand", bands.find(id)); model.addAttribute("senderBands", senders);
        model.addAttribute("today", java.time.LocalDate.now(java.time.ZoneOffset.UTC));
        if (!model.containsAttribute("form")) model.addAttribute("form", senderId == null ? Map.of() : Map.of("senderId", senderId.toString()));
        return view(model, "proposal.create", "date-proposal-form");
    }
    @PostMapping("/bands/{id}/date-proposals")
    public String create(Principal principal, @PathVariable Long id, @RequestParam(required = false) Long senderId,
                         @RequestParam(defaultValue = "") String date, @RequestParam(defaultValue = "") String venue,
                         @RequestParam(defaultValue = "") String message, RedirectAttributes redirect) {
        try {
            if (senderId == null) throw new IllegalArgumentException("proposal.senderError");
            var proposal = service.create(instruments.currentUser(principal), senderId, id, date.strip(), venue.strip(), message.strip());
            redirect.addFlashAttribute("success", "proposal.sent"); return "redirect:/date-proposals/" + proposal.getId();
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            redirect.addFlashAttribute("form", Map.of("senderId", senderId == null ? "" : senderId.toString(), "date", date, "venue", venue, "message", message));
            return "redirect:/bands/" + id + "/date-proposals/new";
        }
    }
    @GetMapping("/date-proposals/{id}")
    public String detail(Principal principal, @PathVariable Long id, Model model) {
        User actor = instruments.currentUser(principal);
        var proposal = service.visible(actor, id);
        boolean sender = proposal.getSender().getCreator().getId().equals(actor.getId());
        model.addAttribute("proposal", proposal); model.addAttribute("history", revisions.findByProposalIdOrderByNumberAsc(id));
        model.addAttribute("ownBand", sender ? proposal.getSender() : proposal.getRecipient());
        model.addAttribute("canRespond", proposal.getStatus() == BandDateProposal.Status.PENDING && proposal.getResponder().getCreator().getId().equals(actor.getId()));
        model.addAttribute("canCancel", proposal.getStatus() == BandDateProposal.Status.PENDING && sender);
        model.addAttribute("canAgree", !service.eligibleSenders(actor, sender ? proposal.getRecipient().getId() : proposal.getSender().getId()).isEmpty());
        model.addAttribute("today", java.time.LocalDate.now(java.time.ZoneOffset.UTC));
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of("date", proposal.getProposedDate().toString(), "venue", proposal.getVenue(), "message", ""));
        return view(model, "proposal.detail", "date-proposal-detail");
    }
    @PostMapping("/date-proposals/{id}/{action:accept|decline|counter|cancel}")
    public String act(Principal principal, @PathVariable Long id, @PathVariable String action, @RequestParam int revision,
                      @RequestParam(defaultValue = "") String date, @RequestParam(defaultValue = "") String venue,
                      @RequestParam(defaultValue = "") String message, RedirectAttributes redirect) {
        try {
            service.act(instruments.currentUser(principal), id, revision, action, date.strip(), venue.strip(), message.strip());
            redirect.addFlashAttribute("success", "proposal.done." + action);
        } catch (BandDateProposalService.ChangedProposal e) {
            redirect.addFlashAttribute("error", "proposal.changedError");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            redirect.addFlashAttribute("form", Map.of("date", date, "venue", venue, "message", message));
        }
        return "redirect:/date-proposals/" + id;
    }
    private String view(Model model, String title, String template) { model.addAttribute("title", title); model.addAttribute("contentTemplate", template); return "layout"; }
}
