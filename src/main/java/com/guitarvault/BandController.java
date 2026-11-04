package com.guitarvault;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class BandController {
    private static final java.util.List<String> FIELDS = java.util.List.of(
            "name", "genre", "city", "description", "spotifyUrl", "deezerUrl", "websiteUrl", "members");
    private final BandService service;
    private final BandRepository bands;
    private final InstrumentService instruments;

    public BandController(BandService service, BandRepository bands, InstrumentService instruments) {
        this.service = service;
        this.bands = bands;
        this.instruments = instruments;
    }

    @GetMapping("/bands")
    public String list(Principal principal, Model model) {
        model.addAttribute("bands", bands.findAllByOrderByIdDesc());
        model.addAttribute("myBands", bands.findByMembersIdOrderByNameAsc(instruments.currentUser(principal).getId()));
        model.addAttribute("title", "nav.bands");
        model.addAttribute("contentTemplate", "bands");
        return "layout";
    }

    @GetMapping("/bands/new")
    public String createForm(Model model) { return form(model, null); }

    @GetMapping("/bands/{id}")
    public String detail(Principal principal, @PathVariable Long id, Model model) {
        Band band = service.find(id);
        model.addAttribute("band", band);
        model.addAttribute("canEditBand", band.getCreator().getId().equals(instruments.currentUser(principal).getId()));
        model.addAttribute("pageTitle", band.getName() + " — Guitar Vault");
        model.addAttribute("contentTemplate", "band-detail");
        return "layout";
    }

    @GetMapping("/bands/{id}/edit")
    public String editForm(Principal principal, @PathVariable Long id, Model model) {
        Band band = service.editable(instruments.currentUser(principal), id);
        model.addAttribute("form", Map.of("name", band.getName(), "genre", band.getGenre(), "city", band.getCity(),
                "description", band.getDescription(), "spotifyUrl", band.getSpotifyUrl(), "deezerUrl", band.getDeezerUrl(),
                "websiteUrl", band.getWebsiteUrl(), "members", band.getMembers().stream()
                        .map(User::getUsername).sorted().collect(Collectors.joining("\n"))));
        return form(model, id);
    }

    @PostMapping("/bands")
    public String create(Principal principal, @RequestParam Map<String, String> params, Model model, RedirectAttributes redirect) {
        return save(principal, null, params, model, redirect);
    }

    @PostMapping("/bands/{id}")
    public String update(Principal principal, @PathVariable Long id, @RequestParam Map<String, String> params,
                         Model model, RedirectAttributes redirect) {
        return save(principal, id, params, model, redirect);
    }

    private String save(Principal principal, Long id, Map<String, String> params, Model model, RedirectAttributes redirect) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : FIELDS) values.put(field, params.getOrDefault(field, "").strip());
        try {
            Band band = service.save(instruments.currentUser(principal), id, values);
            redirect.addFlashAttribute("success", "band.saved");
            return "redirect:/bands/" + band.getId();
        } catch (IllegalArgumentException exception) {
            model.addAttribute("form", values);
            model.addAttribute("error", exception.getMessage());
            return form(model, id);
        }
    }

    private String form(Model model, Long id) {
        model.addAttribute("bandId", id);
        model.addAttribute("title", id == null ? "band.create" : "band.edit");
        model.addAttribute("contentTemplate", "band-form");
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of());
        return "layout";
    }
}
