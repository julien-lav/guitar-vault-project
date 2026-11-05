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

@Controller
@RequestMapping("/notifications")
public class NotificationController {
    private final NotificationRepository notifications;
    private final NotificationService service;
    private final InstrumentService instruments;
    public NotificationController(NotificationRepository notifications, NotificationService service, InstrumentService instruments) {
        this.notifications = notifications; this.service = service; this.instruments = instruments;
    }
    @ModelAttribute
    public void privatePage(HttpServletResponse response) { response.setHeader("Cache-Control", "private, no-store"); }
    @GetMapping
    public String index(Principal principal, @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "false") boolean unread, Model model) {
        User actor = instruments.currentUser(principal);
        if (page < 0 || page > 1000000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var request = PageRequest.of(page, 20);
        model.addAttribute("notificationPage", unread ? notifications.findByRecipientIdAndReadAtIsNullOrderByIdDesc(actor.getId(), request)
                : notifications.findByRecipientIdOrderByIdDesc(actor.getId(), request));
        model.addAttribute("onlyUnread", unread);
        model.addAttribute("title", "notification.title"); model.addAttribute("contentTemplate", "notifications"); return "layout";
    }
    @GetMapping("/unread-count") @ResponseBody
    public Map<String, Long> count(Principal principal) {
        User actor = instruments.currentUser(principal);
        return Map.of("unread", notifications.countByRecipientIdAndReadAtIsNull(actor.getId()));
    }
    @PostMapping("/{id}/open")
    public String open(Principal principal, @PathVariable Long id) { return "redirect:" + service.open(instruments.currentUser(principal), id); }
    @PostMapping("/{id}/read")
    public String read(Principal principal, @PathVariable Long id) { service.markRead(instruments.currentUser(principal), id); return "redirect:/notifications"; }
    @PostMapping("/read-all")
    public String readAll(Principal principal) { service.markAllRead(instruments.currentUser(principal)); return "redirect:/notifications"; }
}
