package com.guitarvault;

import java.security.Principal;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WishlistController {
    private final WishlistRepository wishlist;
    private final InstrumentService instruments;

    public WishlistController(WishlistRepository wishlist, InstrumentService instruments) {
        this.wishlist = wishlist;
        this.instruments = instruments;
    }

    @GetMapping("/wishlist")
    public String list(Principal principal, Model model) {
        model.addAttribute("items", wishlist.findByOwnerIdOrderByIdDesc(instruments.currentUser(principal).getId()));
        model.addAttribute("title", "page.wishlist");
        model.addAttribute("contentTemplate", "wishlist");
        return "layout";
    }

    @GetMapping("/wishlist/new")
    public String createForm(Model model) {
        return form(model, null);
    }

    @GetMapping("/wishlist/{id}/edit")
    public String editForm(Principal principal, @PathVariable Long id, Model model) {
        var item = ownedItem(principal, id);
        model.addAttribute("form", Map.of("name", item.getName(), "brand", item.getBrand(),
                "year", item.getYear() == null ? "" : item.getYear().toString(),
                "notes", item.getNotes() == null ? "" : item.getNotes()));
        return form(model, id);
    }

    @PostMapping("/wishlist")
    public String create(Principal principal, @RequestParam Map<String, String> params,
                         Model model, RedirectAttributes redirect) {
        return save(principal, null, params, model, redirect);
    }

    @PostMapping("/wishlist/{id}")
    public String edit(Principal principal, @PathVariable Long id, @RequestParam Map<String, String> params,
                       Model model, RedirectAttributes redirect) {
        return save(principal, id, params, model, redirect);
    }

    @GetMapping("/wishlist/{id}/delete")
    public String deleteForm(Principal principal, @PathVariable Long id, Model model) {
        model.addAttribute("item", ownedItem(principal, id));
        model.addAttribute("title", "wishlist.remove");
        model.addAttribute("contentTemplate", "wishlist-delete");
        return "layout";
    }

    @PostMapping("/wishlist/{id}/delete")
    public String delete(Principal principal, @PathVariable Long id, RedirectAttributes redirect) {
        wishlist.delete(ownedItem(principal, id));
        redirect.addFlashAttribute("success", "wishlist.deleted");
        return "redirect:/wishlist";
    }

    private String save(Principal principal, Long id, Map<String, String> params,
                        Model model, RedirectAttributes redirect) {
        var owner = instruments.currentUser(principal);
        var item = id == null ? null : ownedItem(principal, id);
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : Set.of("name", "brand", "year", "notes")) {
            values.put(field, params.getOrDefault(field, "").strip());
        }
        String error = null;
        Integer year = null;
        if (values.get("name").isBlank() || values.get("name").length() > 100
                || values.get("brand").isBlank() || values.get("brand").length() > 100) {
            error = "instrument.nameError";
        } else if (values.get("notes").length() > 5000) {
            error = "wishlist.notesError";
        } else if (!values.get("year").isEmpty()) {
            try {
                year = Integer.valueOf(values.get("year"));
                if (year < 1 || year > Year.now().getValue()) error = "instrument.futureYearError";
            } catch (NumberFormatException exception) {
                error = "instrument.yearError";
            }
        }
        if (error != null) {
            model.addAttribute("error", error);
            model.addAttribute("form", values);
            return form(model, id);
        }
        if (item == null) item = new WishlistItem(owner, values.get("name"), values.get("brand"), year, values.get("notes"));
        else item.update(values.get("name"), values.get("brand"), year, values.get("notes"));
        wishlist.save(item);
        redirect.addFlashAttribute("success", id == null ? "wishlist.added" : "wishlist.updated");
        return "redirect:/wishlist";
    }

    private WishlistItem ownedItem(Principal principal, Long id) {
        return wishlist.findByIdAndOwnerId(id, instruments.currentUser(principal).getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String form(Model model, Long id) {
        model.addAttribute("itemId", id);
        model.addAttribute("title", id == null ? "wishlist.add" : "wishlist.edit");
        model.addAttribute("contentTemplate", "wishlist-form");
        model.addAttribute("currentYear", Year.now().getValue());
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of());
        return "layout";
    }
}
