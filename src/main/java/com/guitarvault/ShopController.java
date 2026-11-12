package com.guitarvault;

import java.security.Principal;
import java.util.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class ShopController {
    private final ShopService service;
    private final VintageShopRepository shops;
    private final ShopListingRepository listings;
    private final InstrumentService instruments;
    public ShopController(ShopService service, VintageShopRepository shops, ShopListingRepository listings, InstrumentService instruments) {
        this.service = service; this.shops = shops; this.listings = listings; this.instruments = instruments;
    }
    @ModelAttribute public void privatePage(HttpServletResponse response) { response.setHeader("Cache-Control", "private, no-store"); }
    @GetMapping("/shops")
    public String directory(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("shopPage", shops.directory(query(q), paging(page))); model.addAttribute("q", q);
        return page(model, "shops", "shop.directory");
    }
    @GetMapping("/shops/{id}")
    public String shop(@PathVariable Long id, @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("shop", service.publicShop(id)); model.addAttribute("listingPage", listings.catalogue(id, query(q), paging(page))); model.addAttribute("q", q);
        return page(model, "shop-detail", "shop.profile");
    }
    @GetMapping("/shop-listings/{id}")
    public String listing(@PathVariable Long id, Model model) {
        model.addAttribute("listing", service.publicListing(id)); return page(model, "shop-listing-detail", "shop.instrument");
    }
    @GetMapping("/shop-listings/{id}/photo") @ResponseBody
    public ResponseEntity<byte[]> photo(Principal principal, @PathVariable Long id) {
        var photo = service.photo(principal == null ? null : instruments.currentUser(principal), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.getContentType())).header("Cache-Control", "private, no-store")
                .header("X-Content-Type-Options", "nosniff").body(photo.getData());
    }
    @GetMapping("/my-shop")
    public String mine(Principal principal, @RequestParam(defaultValue = "0") int page, Model model) {
        var actor = instruments.currentUser(principal); var shop = service.ownShop(actor);
        model.addAttribute("shop", shop); model.addAttribute("listingPage", listings.findByShopUserIdOrderByIdDesc(actor.getId(), paging(page)));
        if (!model.containsAttribute("form")) {
            Map<String,String> form = new LinkedHashMap<>(); form.put("version", shop == null ? "-1" : "" + shop.getVersion());
            if (shop != null) { form.put("name", shop.getName()); form.put("location", shop.getLocation()); form.put("description", shop.getDescription()); form.put("contact", shop.getContact()); form.put("openingHours", shop.getOpeningHours()); form.put("published", "" + shop.isPublished()); }
            model.addAttribute("form", form);
        }
        return page(model, "shop-manage", "shop.mine");
    }
    @PostMapping("/my-shop")
    public String saveProfile(Principal principal, @RequestParam long version, @RequestParam Map<String,String> params, Model model) {
        try { service.saveProfile(instruments.currentUser(principal), version, clean(params)); }
        catch (IllegalArgumentException e) { model.addAttribute("form", clean(params)); model.addAttribute("error", e.getMessage()); return mine(principal, 0, model); }
        return "redirect:/my-shop";
    }
    @GetMapping("/my-shop/listings/new")
    public String newListing(Principal principal, Model model) { return listingForm(principal, null, model); }
    @GetMapping("/my-shop/listings/{id}/edit")
    public String editListing(Principal principal, @PathVariable Long id, Model model) { return listingForm(principal, id, model); }
    private String listingForm(Principal principal, Long id, Model model) {
        var actor = instruments.currentUser(principal); var shop = service.ownShop(actor); if (shop == null) throw ShopService.missing();
        var listing = id == null ? null : service.ownedListing(actor, id);
        model.addAttribute("listing", listing); model.addAttribute("shop", shop); model.addAttribute("instruments", instruments.instrumentsFor(actor));
        model.addAttribute("currentYear", java.time.Year.now(java.time.ZoneOffset.UTC).getValue());
        if (!model.containsAttribute("form")) {
            Map<String,String> form = new LinkedHashMap<>(); form.put("version", listing == null ? "-1" : "" + listing.getVersion()); form.put("currency", "EUR"); form.put("stock", "AVAILABLE");
            if (listing != null) { form.put("description", listing.getDescription()); form.put("price", listing.getPrice().toPlainString()); form.put("currency", listing.getCurrency()); form.put("stock", listing.getStock().name()); form.put("published", "" + listing.isPublished()); }
            model.addAttribute("form", form);
        }
        return page(model, "shop-listing-form", "shop.addInstrument");
    }
    @PostMapping({"/my-shop/listings", "/my-shop/listings/{id}"})
    public String saveListing(Principal principal, @PathVariable(required = false) Long id, @RequestParam long version,
            @RequestParam Map<String,String> params, @RequestParam(required = false) MultipartFile photo, Model model) {
        try { service.saveListing(instruments.currentUser(principal), id, version, clean(params), photo); }
        catch (IllegalArgumentException e) { model.addAttribute("form", clean(params)); model.addAttribute("error", e.getMessage()); return listingForm(principal, id, model); }
        return "redirect:/my-shop";
    }
    @ExceptionHandler(ResponseStatusException.class)
    public String conflict(ResponseStatusException e, HttpServletResponse response, Model model) {
        if (e.getStatusCode() != HttpStatus.CONFLICT) throw e;
        response.setStatus(409); model.addAttribute("error", "shop.conflict"); return "shop-error";
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String oversized(HttpServletResponse response, Model model) { response.setStatus(400); model.addAttribute("error", "shop.photoError"); return "shop-error"; }
    static String page(Model model, String template, String title) { model.addAttribute("contentTemplate", template); model.addAttribute("title", title); return "layout"; }
    static PageRequest paging(int page) { if (page < 0 || page > 100000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST); return PageRequest.of(page, 20); }
    static String query(String q) { if (q.length() > 150) throw new ResponseStatusException(HttpStatus.BAD_REQUEST); return q.strip(); }
    static Map<String,String> clean(Map<String,String> params) {
        var result = new LinkedHashMap<String,String>();
        for (String key : List.of("version", "name", "location", "description", "contact", "openingHours", "published", "instrumentId", "title", "brand", "year", "price", "currency", "stock", "removePhoto", "message", "proposedTime", "timeZone")) result.put(key, params.getOrDefault(key, "").strip());
        return result;
    }
}
