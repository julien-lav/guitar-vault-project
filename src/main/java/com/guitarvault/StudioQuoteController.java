package com.guitarvault;

import java.security.Principal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class StudioQuoteController {
    private final StudioQuoteService service;
    private final StudioQuoteRepository quotes;
    private final InstrumentService instruments;
    private final StudioQuoteMessageRepository messages;

    public StudioQuoteController(StudioQuoteService service, StudioQuoteRepository quotes, InstrumentService instruments,
                                 StudioQuoteMessageRepository messages) {
        this.service = service;
        this.quotes = quotes;
        this.instruments = instruments;
        this.messages = messages;
    }

    @GetMapping("/quotes")
    public String index(Principal principal, Model model) {
        User actor = instruments.currentUser(principal);
        model.addAttribute("sentQuotes", quotes.findByRequesterIdOrderByIdDesc(actor.getId()));
        model.addAttribute("receivedQuotes", quotes.findByStudioCreatorIdOrderByIdDesc(actor.getId()));
        model.addAttribute("title", "nav.quotes");
        model.addAttribute("contentTemplate", "quotes");
        return "layout";
    }

    @GetMapping("/studios/{studioId}/quotes/new")
    public String form(Principal principal, @PathVariable Long studioId, Model model) {
        model.addAttribute("studio", service.requestableStudio(instruments.currentUser(principal), studioId));
        model.addAttribute("today", LocalDate.now(ZoneOffset.UTC));
        model.addAttribute("title", "quote.request");
        model.addAttribute("contentTemplate", "quote-form");
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of());
        return "layout";
    }

    @PostMapping("/studios/{studioId}/quotes")
    public String create(Principal principal, @PathVariable Long studioId, @RequestParam Map<String, String> params,
                         Model model, RedirectAttributes redirect) {
        Map<String, String> values = values(params, List.of("title", "description", "startDate", "endDate", "budget"));
        try {
            StudioQuote quote = service.create(instruments.currentUser(principal), studioId, values);
            redirect.addFlashAttribute("success", "quote.sent");
            return "redirect:/quotes/" + quote.getId();
        } catch (IllegalArgumentException exception) {
            model.addAttribute("form", values);
            model.addAttribute("error", exception.getMessage());
            return form(principal, studioId, model);
        }
    }

    @GetMapping("/quotes/{id}")
    public String detail(Principal principal, @PathVariable Long id, Model model) {
        User actor = instruments.currentUser(principal);
        StudioQuote quote = service.visible(actor, id);
        model.addAttribute("quote", quote);
        List<StudioQuoteMessage> conversation = messages.findByQuoteIdOrderByIdAsc(quote.getId());
        if (conversation.isEmpty() && quote.getReply() != null) {
            conversation = List.of(StudioQuoteMessage.legacyReply(quote));
        }
        model.addAttribute("conversation", conversation);
        model.addAttribute("isStudioManager", quote.getStudio().getCreator().getId().equals(actor.getId()));
        model.addAttribute("title", "quote.detail");
        model.addAttribute("contentTemplate", "quote-detail");
        if (!model.containsAttribute("replyForm")) model.addAttribute("replyForm", Map.of());
        return "layout";
    }

    @PostMapping("/quotes/{id}/reply")
    public String reply(Principal principal, @PathVariable Long id, @RequestParam Map<String, String> params,
                        Model model, RedirectAttributes redirect) {
        Map<String, String> values = values(params, List.of("message", "amount"));
        try {
            service.reply(instruments.currentUser(principal), id, values);
            redirect.addFlashAttribute("success", "quote.replySent");
            return "redirect:/quotes/" + id;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("replyForm", values);
            model.addAttribute("error", exception.getMessage());
            return detail(principal, id, model);
        }
    }

    private static Map<String, String> values(Map<String, String> params, List<String> fields) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : fields) values.put(field, params.getOrDefault(field, "").strip());
        return values;
    }
}
