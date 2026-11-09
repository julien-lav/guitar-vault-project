package com.guitarvault;

import java.security.Principal;
import java.util.List;
import java.util.ArrayList;
import org.springframework.util.MultiValueMap;
import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class InstrumentController {
    private final InstrumentService instruments;
    private final LuthierInterventionRepository interventions;

    public InstrumentController(InstrumentService instruments, LuthierInterventionRepository interventions) {
        this.instruments = instruments;
        this.interventions = interventions;
    }

    @GetMapping("/instruments")
    public String instruments(Principal principal, Model model) {
        model.addAttribute("instruments", instruments.instrumentsFor(instruments.currentUser(principal)));
        model.addAttribute("title", "page.instruments");
        model.addAttribute("contentTemplate", "instruments");
        return "layout";
    }

    @GetMapping("/instruments/new")
    public String newInstrument(Model model) {
        model.addAttribute("title", "page.instrumentNew");
        model.addAttribute("contentTemplate", "instrument-form");
        model.addAttribute("currentYear", Year.now().getValue());
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", Map.of("status", "Actif"));
        }
        if (!model.containsAttribute("recordings")) model.addAttribute("recordings", List.of(""));
        if (!model.containsAttribute("concerts")) model.addAttribute("concerts", List.of(""));
        return "layout";
    }

    @GetMapping("/instruments/{id}")
    public String detail(Principal principal, @PathVariable Long id, Model model) {
        User actor = instruments.currentUser(principal);
        Instrument owned = instruments.ownedInstrument(actor, id);
        model.addAttribute("instrument", owned);
        model.addAttribute("visiblePhotos", owned.getPhotos().stream().filter(p -> p.visibleTo(actor)).toList());
        model.addAttribute("interventions", interventions.findByInstrumentIdAndRequesterIdOrderByIdDesc(id, actor.getId()));
        model.addAttribute("title", "page.instrumentDetail");
        model.addAttribute("contentTemplate", "instrument-detail");
        return "layout";
    }

    @GetMapping("/instruments/{id}/photos/{photoId}")
    public ResponseEntity<byte[]> photo(Principal principal, @PathVariable Long id, @PathVariable Long photoId) {
        var instrument = instruments.ownedInstrument(instruments.currentUser(principal), id);
        var photo = instrument.getPhotos().stream().filter(item -> item.getId().equals(photoId) && item.visibleTo(instruments.currentUser(principal))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.getContentType()))
                .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff")
                .body(photo.getData());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String oversizedUpload(Model model) {
        model.addAttribute("error", "instrument.photosError");
        return newInstrument(model);
    }

    @PostMapping("/instruments")
    public String addInstrument(Principal principal, @RequestParam MultiValueMap<String, String> params,
                                @RequestParam(required = false, name = "photos") List<MultipartFile> photos,
                                Model model, RedirectAttributes redirect) {
        User owner = instruments.currentUser(principal);
        Map<String, String> form = new LinkedHashMap<>();
        for (String field : Set.of("name", "brand", "year", "status", "history", "notes", "serialNumber", "previousOwner")) {
            form.put(field, params.getFirst(field) == null ? "" : params.getFirst(field).strip());
        }
        var recordings = cleanEntries(params.get("recordings"));
        var concerts = cleanEntries(params.get("concerts"));
        model.addAttribute("recordings", recordings.isEmpty() ? List.of("") : recordings);
        model.addAttribute("concerts", concerts.isEmpty() ? List.of("") : concerts);
        var validatedPhotos = new ArrayList<PhotoData>();
        Integer year = null;
        String error = null;
        if (form.get("name").isBlank() || form.get("name").length() > 100
                || form.get("brand").isBlank() || form.get("brand").length() > 100) {
            error = "instrument.nameError";
        } else if (!Set.of("Actif", "À réparer", "En vente").contains(form.get("status"))) {
            error = "instrument.statusError";
        } else if (!form.get("year").isEmpty()) {
            try {
                year = Integer.valueOf(form.get("year"));
                if (year < 1 || year > Year.now().getValue()) {
                    error = "instrument.futureYearError";
                }
            } catch (NumberFormatException exception) {
                error = "instrument.yearError";
            }
        }
        if (error == null && (form.get("serialNumber").length() > 100 || form.get("previousOwner").length() > 200)) {
            error = "instrument.identificationError";
        }
        if (error == null && (form.get("history").length() > 10000 || form.get("notes").length() > 5000)) {
            error = "instrument.storyError";
        }
        if (error == null && (recordings.size() > 50 || concerts.size() > 50
                || recordings.stream().anyMatch(entry -> entry.length() > 500)
                || concerts.stream().anyMatch(entry -> entry.length() > 500))) {
            error = "instrument.entriesError";
        }
        if (error == null) {
            try {
                var uploads = photos == null ? List.<MultipartFile>of() : photos.stream().filter(file -> !file.isEmpty()).toList();
                if (uploads.size() > 5) throw new IOException("Too many photos");
                for (var upload : uploads) {
                    if (upload.getSize() > 5 * 1024 * 1024) throw new IOException("Photo too large");
                    byte[] data = upload.getBytes();
                    String contentType = ImageUploads.imageType(data);
                    validatedPhotos.add(new PhotoData(contentType, data));
                }
            } catch (IOException | IllegalArgumentException exception) {
                error = "instrument.photosError";
            }
        }
        if (error != null) {
            model.addAttribute("form", form);
            model.addAttribute("error", error);
            return newInstrument(model);
        }
        // Build the final instrument once all fields and uploads have been validated.
        var saved = new Instrument(form.get("name"), form.get("brand"), year, owner, form.get("status"));
        saved.setSerialNumber(form.get("serialNumber"));
        saved.setPreviousOwner(form.get("previousOwner"));
        saved.setHistory(form.get("history"));
        saved.setNotes(form.get("notes"));
        saved.getRecordings().addAll(recordings);
        saved.getConcerts().addAll(concerts);
        for (var photo : validatedPhotos) {
            saved.getPhotos().add(new InstrumentPhoto(saved, photo.contentType(), photo.data()));
        }
        instruments.save(saved);
        redirect.addFlashAttribute("success", "instrument.success");
        return "redirect:/dashboard";
    }

    private record PhotoData(String contentType, byte[] data) { }

    private static List<String> cleanEntries(List<String> entries) {
        return entries == null ? List.of() : entries.stream().map(String::strip).filter(value -> !value.isEmpty()).toList();
    }

}
