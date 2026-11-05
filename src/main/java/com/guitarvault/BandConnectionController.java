package com.guitarvault;

import java.security.Principal;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class BandConnectionController {
    private final BandConnectionService service;
    private final BandConnectionRepository connections;
    private final InstrumentService instruments;
    public BandConnectionController(BandConnectionService service, BandConnectionRepository connections, InstrumentService instruments) {
        this.service = service;
        this.connections = connections;
        this.instruments = instruments;
    }
    @GetMapping("/band-connections")
    public String index(Principal principal, Model model) {
        User actor = instruments.currentUser(principal);
        var pending = connections.forManager(actor.getId(), BandConnection.Status.PENDING);
        model.addAttribute("incomingConnections", pending.stream().filter(c -> c.getRecipient().getCreator().getId().equals(actor.getId())).toList());
        model.addAttribute("outgoingConnections", pending.stream().filter(c -> c.getSender().getCreator().getId().equals(actor.getId())).toList());
        model.addAttribute("acceptedConnections", connections.forManager(actor.getId(), BandConnection.Status.ACCEPTED));
        model.addAttribute("title", "connection.title");
        model.addAttribute("contentTemplate", "band-connections");
        return "layout";
    }
    @PostMapping("/bands/{id}/connections")
    public String request(Principal principal, @PathVariable Long id, @RequestParam(defaultValue = "") Long senderId,
                          @RequestParam Map<String, String> params, RedirectAttributes redirect) {
        User actor = instruments.currentUser(principal);
        Map<String, String> form = Map.of("senderId", senderId == null ? "" : senderId.toString(),
                "type", params.getOrDefault("type", "").strip(), "playedOn", params.getOrDefault("playedOn", "").strip(),
                "venue", params.getOrDefault("venue", "").strip());
        try {
            if (senderId == null) throw new IllegalArgumentException("connection.senderError");
            service.request(actor, senderId, id, form.get("type"), form.get("playedOn"), form.get("venue"));
            redirect.addFlashAttribute("success", "connection.sent");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            redirect.addFlashAttribute("connectionForm", form);
        }
        return "redirect:/bands/" + id + "#band-network";
    }
    @PostMapping("/band-connections/{id}/{action:accept|decline|cancel|remove}")
    public String act(Principal principal, @PathVariable Long id, @PathVariable String action, RedirectAttributes redirect) {
        try {
            service.act(instruments.currentUser(principal), id, action);
            redirect.addFlashAttribute("success", "connection.done." + action);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/band-connections";
    }
    @GetMapping("/band-connections/{id}/remove")
    public String removeForm(Principal principal, @PathVariable Long id, Model model) {
        BandConnection connection = service.removable(instruments.currentUser(principal), id);
        model.addAttribute("connection", connection);
        model.addAttribute("title", "connection.remove");
        model.addAttribute("contentTemplate", "band-connection-confirm");
        return "layout";
    }
}
