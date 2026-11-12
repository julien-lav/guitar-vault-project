package com.guitarvault;

import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShopContactService {
    private final VintageShopRepository shops;
    private final ShopService shopService;
    private final ShopConversationRepository conversations;
    private final ShopMessageRepository messages;
    private final NotificationService notifications;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    public ShopContactService(VintageShopRepository shops, ShopService shopService, ShopConversationRepository conversations,
            ShopMessageRepository messages, NotificationService notifications) {
        this.shops = shops; this.shopService = shopService; this.conversations = conversations; this.messages = messages; this.notifications = notifications;
    }
    public VintageShop requestable(User actor, Long id) {
        var shop = shopService.publicShop(id);
        if (!actor.isEnabled() || shop.getUser().getId().equals(actor.getId())) throw ShopService.missing();
        return shop;
    }
    @Transactional
    public ShopConversation create(User actor, Long shopId, Long listingId, boolean scheduled, Map<String,String> form) {
        var locked = shops.findForUpdate(shopId).orElseThrow(ShopService::missing); em.refresh(locked);
        var shop = requestable(actor, shopId);
        ShopListing listing = null;
        if (listingId != null) {
            listing = shopService.publicListing(listingId);
            if (!listing.getShop().getId().equals(shopId) || listing.getStock() == ShopListing.Stock.SOLD) throw ShopService.missing();
        }
        var conversation = new ShopConversation(shop, actor, ShopService.required(form, "title", 150), listing);
        if (scheduled) conversation.propose(actor, AppointmentTimes.parse(form.get("proposedTime"), form.get("timeZone")), form.get("timeZone"));
        String body = ShopService.required(form, "message", 2000);
        conversations.saveAndFlush(conversation);
        record(actor, conversation, scheduled ? "PROPOSAL" : "MESSAGE", body);
        return conversation;
    }
    public ShopConversation visible(User actor, Long id) {
        return conversations.findById(id).filter(c -> c.getRequester().getId().equals(actor.getId()) || c.getShop().getUser().getId().equals(actor.getId())).orElseThrow(ShopService::missing);
    }
    private ShopConversation locked(User actor, Long id, long version) {
        Long shopId = conversations.visibleShopId(id, actor.getId()).orElseThrow(ShopService::missing);
        var shop = shops.findForUpdate(shopId).orElseThrow(ShopService::missing); em.refresh(shop);
        var c = conversations.findForUpdate(id, actor.getId()).orElseThrow(ShopService::missing); em.refresh(c);
        if (c.getVersion() != version || !c.isOpen() || !actor.isEnabled() || !c.getRequester().isEnabled() || !shop.getUser().isEnabled()) throw ShopService.conflict();
        return c;
    }
    @Transactional
    public void act(User actor, Long id, long version, String action, Map<String,String> form) {
        var c = locked(actor, id, version);
        String body = ""; String kind;
        switch (action) {
            case "messages" -> { body = ShopService.required(form, "message", 2000); kind = "MESSAGE"; }
            case "proposals" -> {
                if (c.getStatus() != ShopConversation.Status.PENDING) throw ShopService.conflict();
                body = ShopService.required(form, "message", 2000);
                c.propose(actor, AppointmentTimes.parse(form.get("proposedTime"), form.get("timeZone")), form.get("timeZone")); kind = "PROPOSAL";
            }
            case "confirm", "decline" -> {
                if (c.getStatus() != ShopConversation.Status.PENDING || !c.isScheduled() || c.getProposer().getId().equals(actor.getId())) throw ShopService.conflict();
                if (action.equals("confirm")) {
                    if (!c.getProposedAt().isAfter(Instant.now()) || conversations.overlaps(c.getShop().getId(), c.getId(), c.getProposedAt().minusSeconds(1800), c.getProposedAt().plusSeconds(1800))) throw ShopService.conflict();
                    c.resolve(ShopConversation.Status.CONFIRMED); kind = "CONFIRMED";
                } else { c.resolve(ShopConversation.Status.DECLINED); kind = "DECLINED"; }
            }
            case "cancel" -> { c.resolve(ShopConversation.Status.CANCELLED); kind = "CANCELLED"; }
            default -> throw ShopService.invalid();
        }
        c.touch(); record(actor, c, kind, body);
    }
    private void record(User actor, ShopConversation c, String kind, String body) {
        messages.save(new ShopMessage(c, actor, kind, body)); conversations.flush();
        var recipient = c.getRequester().getId().equals(actor.getId()) ? c.getShop().getUser() : c.getRequester();
        notifications.send(actor, recipient, Notification.Kind.SHOP_CONVERSATION, c.getTitle(), "/shop-conversations/" + c.getId());
    }
}
