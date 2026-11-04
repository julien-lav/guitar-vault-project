package com.guitarvault;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ProfileController {
    private final ProfileService profiles;
    private final InstrumentService instruments;
    private final BandRepository bands;
    private final StudioRepository studios;

    public ProfileController(ProfileService profiles, InstrumentService instruments, BandRepository bands, StudioRepository studios) {
        this.profiles = profiles;
        this.instruments = instruments;
        this.bands = bands;
        this.studios = studios;
    }

    @GetMapping("/profile")
    public String mine(Principal principal) {
        return "redirect:/musicians/" + instruments.currentUser(principal).getId();
    }

    @GetMapping("/musicians/{id}")
    public String show(Principal principal, @PathVariable Long id, Model model) {
        var profile = profiles.view(id);
        model.addAttribute("profile", profile);
        model.addAttribute("canEditProfile", id.equals(instruments.currentUser(principal).getId()));
        model.addAttribute("bands", bands.findByMembersIdOrderByNameAsc(id));
        model.addAttribute("studios", studios.findByCreatorIdOrderByNameAsc(id));
        model.addAttribute("pageTitle", profile.displayName() + " — Guitar Vault");
        model.addAttribute("contentTemplate", "musician-detail");
        return "layout";
    }

    @GetMapping("/musicians/{id}/photo")
    public ResponseEntity<byte[]> photo(Principal principal, @PathVariable Long id) {
        instruments.currentUser(principal);
        var photo = profiles.photo(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.getContentType()))
                .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff")
                .body(photo.getData());
    }

    @GetMapping("/profile/edit")
    public String edit(Principal principal, Model model) {
        Long id = instruments.currentUser(principal).getId();
        var profile = profiles.view(id);
        model.addAttribute("profile", profile);
        if (!model.containsAttribute("form")) model.addAttribute("form", profiles.form(id));
        if (!model.containsAttribute("selectedActivities")) {
            model.addAttribute("selectedActivities", profile.activities().stream().map(Enum::name).toList());
        }
        model.addAttribute("title", "profile.edit");
        model.addAttribute("contentTemplate", "profile-form");
        return "layout";
    }

    @PostMapping("/profile")
    public String save(Principal principal, @RequestParam Map<String, String> params,
                       @RequestParam(name = "activities", required = false) List<String> activities,
                       @RequestParam(required = false) MultipartFile photo,
                       @RequestParam(defaultValue = "false") boolean removePhoto,
                       Model model, RedirectAttributes redirect) {
        Long id = instruments.currentUser(principal).getId();
        Map<String, String> form = new LinkedHashMap<>();
        for (String field : List.of("displayName", "instrument", "level", "city", "country", "bio", "spotifyUrl", "deezerUrl", "websiteUrl")) {
            form.put(field, params.getOrDefault(field, "").strip());
        }
        activities = activities == null ? List.of() : activities;
        try {
            profiles.save(id, form, activities, photo, removePhoto);
            redirect.addFlashAttribute("success", "profile.saved");
            return "redirect:/musicians/" + id;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("form", form);
            model.addAttribute("selectedActivities", activities);
            model.addAttribute("error", exception.getMessage());
            return edit(principal, model);
        }
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String oversizedUpload(Principal principal, Model model) {
        model.addAttribute("error", "profile.photoError");
        return edit(principal, model);
    }
}
