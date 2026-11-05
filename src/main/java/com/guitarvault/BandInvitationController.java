package com.guitarvault;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class BandInvitationController {
    private final BandService bands;
    private final BandInvitationRepository invitations;
    private final InstrumentService instruments;

    public BandInvitationController(BandService bands, BandInvitationRepository invitations, InstrumentService instruments) {
        this.bands = bands;
        this.invitations = invitations;
        this.instruments = instruments;
    }

    @GetMapping("/invitations")
    public String index(Principal principal, Model model) {
        Long id = instruments.currentUser(principal).getId();
        model.addAttribute("received", invitations.findByInviteeIdAndStatusOrderByIdDesc(id, BandInvitation.Status.PENDING));
        model.addAttribute("sent", invitations.findByBandCreatorIdAndStatusOrderByIdDesc(id, BandInvitation.Status.PENDING));
        model.addAttribute("title", "nav.invitations");
        model.addAttribute("contentTemplate", "band-invitations");
        return "layout";
    }

    @PostMapping("/bands/{id}/invitations")
    public String invite(Principal principal, @PathVariable Long id, @RequestParam(defaultValue = "") String username,
                         RedirectAttributes redirect) {
        try {
            bands.invite(instruments.currentUser(principal), id, username.strip());
            redirect.addFlashAttribute("success", "invitation.sent");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
            redirect.addFlashAttribute("inviteUsername", username);
        }
        return "redirect:/bands/" + id;
    }

    @PostMapping("/invitations/{id}/accept")
    public String accept(Principal principal, @PathVariable Long id, RedirectAttributes redirect) {
        return respond(principal, id, true, redirect);
    }

    @PostMapping("/invitations/{id}/decline")
    public String decline(Principal principal, @PathVariable Long id, RedirectAttributes redirect) {
        return respond(principal, id, false, redirect);
    }

    private String respond(Principal principal, Long id, boolean accept, RedirectAttributes redirect) {
        try {
            bands.respond(instruments.currentUser(principal), id, accept);
            redirect.addFlashAttribute("success", accept ? "invitation.accepted" : "invitation.declined");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/invitations";
    }

    @PostMapping("/invitations/{id}/cancel")
    public String cancel(Principal principal, @PathVariable Long id, RedirectAttributes redirect) {
        bands.cancel(instruments.currentUser(principal), id);
        redirect.addFlashAttribute("success", "invitation.cancelled");
        return "redirect:/invitations";
    }

    @GetMapping("/bands/{id}/leave")
    public String leaveForm(Principal principal, @PathVariable Long id, Model model) {
        Band band = bands.leaveable(instruments.currentUser(principal), id);
        return confirmation(model, band, "/bands/" + id + "/leave", "invitation.leave", "invitation.leavePrompt", band.getName());
    }

    @PostMapping("/bands/{id}/leave")
    public String leave(Principal principal, @PathVariable Long id, RedirectAttributes redirect) {
        bands.leave(instruments.currentUser(principal), id);
        redirect.addFlashAttribute("success", "invitation.left");
        return "redirect:/invitations";
    }

    @GetMapping("/bands/{id}/members/{memberId}/remove")
    public String removeForm(Principal principal, @PathVariable Long id, @PathVariable Long memberId, Model model) {
        User member = bands.removableMember(instruments.currentUser(principal), id, memberId);
        return confirmation(model, bands.find(id), "/bands/" + id + "/members/" + memberId + "/remove",
                "invitation.remove", "invitation.removePrompt", member.getUsername());
    }

    @PostMapping("/bands/{id}/members/{memberId}/remove")
    public String remove(Principal principal, @PathVariable Long id, @PathVariable Long memberId, RedirectAttributes redirect) {
        bands.removeMember(instruments.currentUser(principal), id, memberId);
        redirect.addFlashAttribute("success", "invitation.removed");
        return "redirect:/bands/" + id;
    }

    private String confirmation(Model model, Band band, String action, String title, String prompt, String target) {
        model.addAttribute("band", band);
        model.addAttribute("action", action);
        model.addAttribute("title", title);
        model.addAttribute("prompt", prompt);
        model.addAttribute("target", target);
        model.addAttribute("contentTemplate", "band-member-confirm");
        return "layout";
    }
}
