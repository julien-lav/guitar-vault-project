package com.guitarvault;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class StudioController {
    private static final List<String> FIELDS = List.of("name", "city", "country", "description", "services", "equipment", "websiteUrl", "contactEmail", "hourlyRate", "capacity", "mixingRate", "masteringRate", "dayRate", "pricingNotes");
    private final StudioRepository studios;
    private final StudioService service;
    private final InstrumentService instruments;
    private final StudioPhotoRepository photos;

    public StudioController(StudioRepository studios, StudioService service, InstrumentService instruments, StudioPhotoRepository photos) {
        this.studios = studios;
        this.service = service;
        this.instruments = instruments;
        this.photos = photos;
    }

    @GetMapping("/studios")
    public String list(Principal principal, @RequestParam Map<String, String> params, Model model) {
        Map<String, String> filters = new LinkedHashMap<>();
        for (String field : List.of("city", "country", "minPrice", "maxPrice")) {
            filters.put(field, params.getOrDefault(field, "").strip());
        }
        model.addAttribute("filters", filters);
        model.addAttribute("hasFilters", filters.values().stream().anyMatch(value -> !value.isEmpty()));
        try {
            model.addAttribute("studios", service.search(filters));
        } catch (IllegalArgumentException exception) {
            model.addAttribute("studios", List.of());
            model.addAttribute("searchError", exception.getMessage());
        }
        model.addAttribute("myStudios", studios.findByCreatorIdOrderByNameAsc(instruments.currentUser(principal).getId()));
        model.addAttribute("title", "nav.studios");
        model.addAttribute("contentTemplate", "studios");
        return "layout";
    }

    @GetMapping("/studios/new")
    public String createForm(Model model) { return form(model, null); }

    @GetMapping("/studios/{id}")
    public String detail(Principal principal, @PathVariable Long id, Model model) {
        Studio studio = service.find(id);
        model.addAttribute("studio", studio);
        model.addAttribute("photos", photos.findByStudioIdOrderByIdAsc(id));
        model.addAttribute("canEditStudio", studio.getCreator().getId().equals(instruments.currentUser(principal).getId()));
        model.addAttribute("pageTitle", studio.getName() + " — Guitar Vault");
        model.addAttribute("contentTemplate", "studio-detail");
        return "layout";
    }

    @GetMapping("/studios/{id}/edit")
    public String editForm(Principal principal, @PathVariable Long id, Model model) {
        Studio studio = service.editable(instruments.currentUser(principal), id);
        model.addAttribute("form", Map.ofEntries(
                Map.entry("name", value(studio.getName())), Map.entry("city", value(studio.getCity())),
                Map.entry("country", value(studio.getCountry())), Map.entry("description", value(studio.getDescription())),
                Map.entry("services", value(studio.getServices())), Map.entry("equipment", value(studio.getEquipment())),
                Map.entry("websiteUrl", value(studio.getWebsiteUrl())), Map.entry("contactEmail", value(studio.getContactEmail())),
                Map.entry("hourlyRate", value(studio.getHourlyRate())), Map.entry("capacity", value(studio.getCapacity())),
                Map.entry("mixingRate", value(studio.getMixingRate())), Map.entry("masteringRate", value(studio.getMasteringRate())),
                Map.entry("dayRate", value(studio.getDayRate())), Map.entry("pricingNotes", value(studio.getPricingNotes()))));
        return form(model, id);
    }

    @PostMapping("/studios")
    public String create(Principal principal, @RequestParam Map<String, String> params,
                         @RequestParam(name = "photos", required = false) List<MultipartFile> uploads,
                         @RequestParam(name = "removePhotos", required = false) List<Long> removals,
                         Model model, RedirectAttributes redirect) {
        return save(principal, null, params, uploads, removals, model, redirect);
    }

    @PostMapping("/studios/{id}")
    public String edit(Principal principal, @PathVariable Long id, @RequestParam Map<String, String> params,
                       @RequestParam(name = "photos", required = false) List<MultipartFile> uploads,
                       @RequestParam(name = "removePhotos", required = false) List<Long> removals,
                       Model model, RedirectAttributes redirect) {
        return save(principal, id, params, uploads, removals, model, redirect);
    }

    @GetMapping("/studios/{id}/photos/{photoId}")
    public ResponseEntity<byte[]> photo(Principal principal, @PathVariable Long id, @PathVariable Long photoId) {
        instruments.currentUser(principal);
        service.find(id);
        var photo = photos.findByIdAndStudioId(photoId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.getContentType()))
                .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff").body(photo.getData());
    }

    private String save(Principal principal, Long id, Map<String, String> params, List<MultipartFile> uploads,
                        List<Long> removals, Model model, RedirectAttributes redirect) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : FIELDS) values.put(field, params.getOrDefault(field, "").strip());
        try {
            Studio studio = service.save(instruments.currentUser(principal), id, values,
                    uploads == null ? List.of() : uploads, removals == null ? List.of() : removals);
            redirect.addFlashAttribute("success", "studio.saved");
            return "redirect:/studios/" + studio.getId();
        } catch (IllegalArgumentException exception) {
            model.addAttribute("form", values);
            model.addAttribute("selectedPhotos", removals == null ? List.of() : removals);
            model.addAttribute("error", exception.getMessage());
            return form(model, id);
        }
    }

    private String form(Model model, Long id) {
        model.addAttribute("studioId", id);
        model.addAttribute("title", id == null ? "studio.create" : "studio.edit");
        model.addAttribute("contentTemplate", "studio-form");
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of());
        model.addAttribute("photos", id == null ? List.of() : photos.findByStudioIdOrderByIdAsc(id));
        if (!model.containsAttribute("selectedPhotos")) model.addAttribute("selectedPhotos", List.of());
        return "layout";
    }

    private static String value(Object value) { return value == null ? "" : value.toString(); }
}
