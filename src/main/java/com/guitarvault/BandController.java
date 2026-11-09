package com.guitarvault;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class BandController {
    private static final java.util.List<String> FIELDS = java.util.List.of(
            "name", "genre", "region", "city", "description", "spotifyUrl", "deezerUrl", "websiteUrl", "members");
    private final BandService service;
    private final BandRepository bands;
    private final InstrumentService instruments;
    private final BandInvitationRepository invitations;
    private final BandConnectionRepository connections;
    private final ConcertRepository concerts;
    private final ConcertPosterRepository concertPosters;
    private final BandDateProposalService dateProposals;

    public BandController(BandService service, BandRepository bands, InstrumentService instruments, BandInvitationRepository invitations, BandConnectionRepository connections, ConcertRepository concerts, ConcertPosterRepository concertPosters, BandDateProposalService dateProposals) {
        this.service = service;
        this.bands = bands;
        this.instruments = instruments;
        this.invitations = invitations;
        this.connections = connections;
        this.concerts = concerts;
        this.concertPosters = concertPosters;
        this.dateProposals = dateProposals;
    }

    @GetMapping("/bands")
    public String list(Principal principal, @RequestParam(defaultValue = "") String genre,
                       @RequestParam(defaultValue = "") String region, @RequestParam(defaultValue = "") String city, Model model) {
        genre = genre.strip();
        region = region.strip();
        city = city.strip();
        model.addAttribute("filters", Map.of("genre", genre, "region", region, "city", city));
        model.addAttribute("hasFilters", !genre.isEmpty() || !region.isEmpty() || !city.isEmpty());
        try {
            model.addAttribute("bands", service.search(genre, region, city));
        } catch (IllegalArgumentException exception) {
            model.addAttribute("bands", java.util.List.of());
            model.addAttribute("error", exception.getMessage());
        }
        model.addAttribute("genreOptions", service.genreOptions());
        model.addAttribute("regionOptions", bands.regionOptions());
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
        ContentVisibility.require(!band.isModerationHidden());
        User actor = instruments.currentUser(principal);
        boolean creator = band.getCreator().getId().equals(actor.getId());
        model.addAttribute("band", band);
        var bandConcerts = concerts.forBand(id, ConcertParticipation.Status.ACCEPTED);
        model.addAttribute("concertPosterIds", bandConcerts.isEmpty() ? java.util.Set.of() : java.util.Set.copyOf(
                concertPosters.concertIdsWithPoster(bandConcerts.stream().map(Concert::getId).toList())));
        var today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        model.addAttribute("upcomingConcerts", bandConcerts.stream().filter(c -> !c.isCancelled() && !c.getDate().isBefore(today))
                .sorted(java.util.Comparator.comparing(Concert::getDate).thenComparing(Concert::getId)).toList());
        model.addAttribute("pastConcerts", bandConcerts.stream().filter(c -> !c.isCancelled() && c.getDate().isBefore(today)).toList());
        model.addAttribute("cancelledConcerts", bandConcerts.stream().filter(Concert::isCancelled).toList());
        var accepted = connections.forBand(id, BandConnection.Status.ACCEPTED);
        model.addAttribute("friendConnections", accepted.stream().filter(c -> c.getType() == BandConnection.Type.FRIEND).toList());
        model.addAttribute("playedConnections", accepted.stream().filter(c -> c.getType() == BandConnection.Type.PLAYED_TOGETHER).toList());
        model.addAttribute("networkBands", bands.findByCreatorIdOrderByNameAsc(actor.getId()).stream().filter(b -> !b.getId().equals(id)).toList());
        model.addAttribute("pendingConnections", creator ? connections.forBand(id, BandConnection.Status.PENDING) : java.util.List.of());
        if (!model.containsAttribute("connectionForm")) model.addAttribute("connectionForm", Map.of());
        model.addAttribute("canEditBand", creator);
        model.addAttribute("canProposeDate", !dateProposals.eligibleSenders(actor, id).isEmpty());
        model.addAttribute("isBandMember", band.getMembers().stream().anyMatch(member -> member.getId().equals(actor.getId())));
        model.addAttribute("pendingInvitations", creator ? invitations.findByBandIdAndStatusOrderByIdDesc(id, BandInvitation.Status.PENDING) : java.util.List.of());
        model.addAttribute("myInvitation", invitations.findByBandIdAndInviteeId(id, actor.getId())
                .filter(invitation -> invitation.getStatus() == BandInvitation.Status.PENDING).orElse(null));
        model.addAttribute("pageTitle", band.getName() + " — Guitar Vault");
        model.addAttribute("contentTemplate", "band-detail");
        return "layout";
    }

    @GetMapping("/bands/{id}/edit")
    public String editForm(Principal principal, @PathVariable Long id, Model model) {
        Band band = service.editable(instruments.currentUser(principal), id);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", band.getName());
        values.put("genres", String.join(", ", band.getDisplayGenres()));
        values.put("region", band.getRegion());
        values.put("city", band.getCity());
        values.put("description", band.getDescription());
        values.put("spotifyUrl", band.getSpotifyUrl());
        values.put("deezerUrl", band.getDeezerUrl());
        values.put("websiteUrl", band.getWebsiteUrl());
        values.put("members", "");
        model.addAttribute("form", values);
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
        if (params.containsKey("genres")) values.put("genres", params.get("genres").strip());
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
        model.addAttribute("regionOptions", bands.regionOptions());
        if (!model.containsAttribute("form")) model.addAttribute("form", Map.of());
        return "layout";
    }
}
