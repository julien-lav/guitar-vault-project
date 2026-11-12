package com.guitarvault;

import java.security.Principal;
import java.time.ZoneId;
import java.util.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class ShopContactController {
    private final ShopContactService service;
    private final ShopService shops;
    private final ShopConversationRepository conversations;
    private final ShopMessageRepository messages;
    private final InstrumentService instruments;
    public ShopContactController(ShopContactService service, ShopService shops, ShopConversationRepository conversations, ShopMessageRepository messages, InstrumentService instruments) {
        this.service = service; this.shops = shops; this.conversations = conversations; this.messages = messages; this.instruments = instruments;
    }
    @ModelAttribute public void privatePage(HttpServletResponse response) { response.setHeader("Cache-Control", "private, no-store"); }
    @GetMapping("/shop-conversations")
    public String inbox(Principal principal, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("conversationPage", conversations.inbox(instruments.currentUser(principal).getId(), ShopController.paging(page)));
        return ShopController.page(model, "shop-conversations", "shop.conversations");
    }
    @GetMapping({"/shops/{id}/contact", "/shops/{id}/appointments/new"})
    public String form(Principal principal, @PathVariable Long id, @RequestParam(required = false) Long listingId,
            jakarta.servlet.http.HttpServletRequest request, Model model) {
        var shop = service.requestable(instruments.currentUser(principal), id);
        ShopListing listing = null;
        if (listingId != null) { listing = shops.publicListing(listingId); if (!listing.getShop().getId().equals(id) || listing.getStock() == ShopListing.Stock.SOLD) throw ShopService.missing(); }
        model.addAttribute("shop", shop); model.addAttribute("listing", listing);
        model.addAttribute("scheduled", request.getRequestURI().endsWith("/appointments/new"));
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of("timeZone", "UTC"));
        zones(model); return ShopController.page(model, "shop-contact", "shop.contact");
    }
    @PostMapping({"/shops/{id}/contact", "/shops/{id}/appointments"})
    public String create(Principal principal, @PathVariable Long id, @RequestParam(required = false) Long listingId,
            @RequestParam Map<String,String> params, jakarta.servlet.http.HttpServletRequest request, Model model) {
        boolean scheduled = request.getRequestURI().endsWith("/appointments");
        try { return "redirect:/shop-conversations/" + service.create(instruments.currentUser(principal), id, listingId, scheduled, ShopController.clean(params)).getId(); }
        catch (IllegalArgumentException e) {
            model.addAttribute("form", ShopController.clean(params)); model.addAttribute("error", e.getMessage());
            String result = form(principal, id, listingId, request, model); model.addAttribute("scheduled", scheduled); return result;
        }
    }
    @GetMapping("/shop-conversations/{id}")
    public String detail(Principal principal, @PathVariable Long id, Model model) {
        var actor = instruments.currentUser(principal); var c = service.visible(actor, id);
        model.addAttribute("conversation", c); model.addAttribute("messages", messages.findByConversationIdOrderByIdAsc(id));
        model.addAttribute("canRespond", c.isScheduled() && c.getStatus() == ShopConversation.Status.PENDING && !c.getProposer().getId().equals(actor.getId()));
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of("timeZone", c.getTimeZone() == null ? "UTC" : c.getTimeZone()));
        zones(model); return ShopController.page(model, "shop-conversation", "shop.conversations");
    }
    @PostMapping("/shop-conversations/{id}/{action:messages|proposals|confirm|decline|cancel}")
    public String act(Principal principal, @PathVariable Long id, @PathVariable String action, @RequestParam long version, @RequestParam Map<String,String> params, Model model) {
        try { service.act(instruments.currentUser(principal), id, version, action, ShopController.clean(params)); }
        catch (IllegalArgumentException e) { model.addAttribute("form", ShopController.clean(params)); model.addAttribute("error", e.getMessage()); return detail(principal, id, model); }
        return "redirect:/shop-conversations/" + id;
    }
    @ExceptionHandler(ResponseStatusException.class)
    public String conflict(ResponseStatusException e, HttpServletResponse response, Model model) {
        if (e.getStatusCode() != HttpStatus.CONFLICT) throw e;
        response.setStatus(409); model.addAttribute("error", "shop.conflict"); return "shop-error";
    }
    private static void zones(Model model) { model.addAttribute("timeZones", ZoneId.getAvailableZoneIds().stream().sorted().toList()); }
}
