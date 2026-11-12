package com.guitarvault;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ShopService {
    private final VintageShopRepository shops;
    private final ShopListingRepository listings;
    private final ShopListingPhotoRepository photos;
    private final InstrumentRepository instruments;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    public ShopService(VintageShopRepository shops, ShopListingRepository listings, ShopListingPhotoRepository photos, InstrumentRepository instruments) {
        this.shops = shops; this.listings = listings; this.photos = photos; this.instruments = instruments;
    }
    public VintageShop publicShop(Long id) { return shops.findById(id).filter(ShopService::publiclyVisible).orElseThrow(ShopService::missing); }
    static boolean publiclyVisible(VintageShop shop) { return shop.isPublished() && shop.getUser().isEnabled() && !shop.getUser().isModerationHidden(); }
    public VintageShop ownShop(User actor) { return shops.findByUserId(actor.getId()).orElse(null); }
    private VintageShop requiredShop(User actor) { var s = ownShop(actor); if (s == null) throw missing(); return s; }
    @Transactional
    public void saveProfile(User actor, long version, Map<String,String> form) {
        if (!actor.isEnabled()) throw missing();
        // Lock the account as well: two first-time submissions must not create duplicate profiles.
        em.find(User.class, actor.getId(), jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        var previous = ownShop(actor);
        var shop = previous == null ? new VintageShop(actor) : shops.findForUpdate(previous.getId()).orElseThrow(ShopService::missing);
        if ((previous == null ? -1 : shop.getVersion()) != version) throw conflict();
        shop.update(required(form, "name", 150), required(form, "location", 200), required(form, "description", 3000),
                required(form, "contact", 300), required(form, "openingHours", 500), "true".equals(form.get("published")));
        shops.saveAndFlush(shop);
    }
    public ShopListing ownedListing(User actor, Long id) {
        return listings.findById(id).filter(l -> l.getShop().getUser().getId().equals(actor.getId())).orElseThrow(ShopService::missing);
    }
    public ShopListing publicListing(Long id) {
        return listings.findById(id).filter(l -> publiclyVisible(l.getShop()) && l.isPublished()
                && l.getInstrument().getOwner().getId().equals(l.getShop().getUser().getId())).orElseThrow(ShopService::missing);
    }
    public ShopListingPhoto photo(User actor, Long id) {
        var listing = listings.findById(id).orElseThrow(ShopService::missing);
        if (actor == null || !listing.getShop().getUser().getId().equals(actor.getId())) publicListing(id);
        return photos.findById(id).orElseThrow(ShopService::missing);
    }
    @Transactional
    public ShopListing saveListing(User actor, Long id, long version, Map<String,String> form, MultipartFile photo) {
        if (!actor.isEnabled()) throw missing();
        var shop = requiredShop(actor);
        String amount = form.getOrDefault("price", ""); String currency = form.getOrDefault("currency", "");
        if (!amount.matches("[0-9]{1,9}(\\.[0-9]{1,2})?") || !Set.of("EUR", "USD", "GBP", "CAD", "CHF").contains(currency)) throw invalid();
        ShopListing.Stock stock;
        try { stock = ShopListing.Stock.valueOf(form.getOrDefault("stock", "AVAILABLE")); } catch (IllegalArgumentException e) { throw invalid(); }
        String description = required(form, "description", 3000);
        byte[] bytes = null; String type = null;
        if (photo != null && !photo.isEmpty()) {
            try { if (photo.getSize() > 5 * 1024 * 1024) throw new java.io.IOException(); bytes = photo.getBytes(); type = ImageUploads.imageType(bytes); }
            catch (java.io.IOException e) { throw new IllegalArgumentException("shop.photoError"); }
        }
        ShopListing listing;
        if (id == null) {
            Instrument instrument;
            String source = form.getOrDefault("instrumentId", "");
            if (source.isBlank()) {
                Integer year = null;
                try { if (!form.getOrDefault("year", "").isBlank()) { year = Integer.valueOf(form.get("year")); if (year < 1 || year > java.time.Year.now(java.time.ZoneOffset.UTC).getValue()) throw invalid(); } }
                catch (NumberFormatException e) { throw invalid(); }
                instrument = instruments.saveAndFlush(new Instrument(required(form, "title", 100), required(form, "brand", 100), year, actor, "Actif"));
            } else {
                Long instrumentId;
                try { instrumentId = Long.valueOf(source); } catch (NumberFormatException e) { throw invalid(); }
                instrument = instruments.findForUpdate(instrumentId).orElseThrow(ShopService::missing); em.refresh(instrument);
                if (!instrument.getOwner().getId().equals(actor.getId())) throw missing();
            }
            if (version != -1) throw conflict();
            listing = new ShopListing(shop, instrument);
        } else {
            var visible = ownedListing(actor, id);
            var instrument = instruments.findForUpdate(visible.getInstrument().getId()).orElseThrow(ShopService::missing); em.refresh(instrument);
            listing = listings.findForUpdate(id).orElseThrow(ShopService::missing); em.refresh(listing);
            if (listing.getVersion() != version) throw conflict();
            if (!instrument.getOwner().getId().equals(actor.getId()) && "true".equals(form.get("published"))) throw conflict();
        }
        if ("true".equals(form.get("published")) && stock != ShopListing.Stock.SOLD && listings.hasOtherLiveListing(listing.getInstrument().getId(), listing.getId())) throw conflict();
        listing.update(description, new BigDecimal(amount).setScale(2), currency, stock, "true".equals(form.get("published")));
        if (bytes != null) listing.photo(true);
        if ("true".equals(form.get("removePhoto")) && bytes == null) listing.photo(false);
        listing.touch();
        listings.saveAndFlush(listing);
        if ("true".equals(form.get("removePhoto")) && bytes == null) { photos.deleteById(listing.getId()); listing.photo(false); }
        if (bytes != null) {
            var previous = photos.findById(listing.getId()).orElse(null);
            if (previous == null) photos.save(new ShopListingPhoto(listing, type, bytes)); else previous.replace(type, bytes);
        }
        return listing;
    }
    static String required(Map<String,String> form, String key, int max) {
        String value = form.getOrDefault(key, "").strip(); if (value.isBlank() || value.length() > max) throw invalid(); return value;
    }
    static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
    static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "shop.conflict"); }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("shop.invalid"); }
}
