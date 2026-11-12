package com.guitarvault;

import jakarta.persistence.*;
import java.time.*;
import java.math.BigDecimal;

@Entity
@Table(name = "shop_conversations", indexes = {@Index(name = "idx_shop_conversation", columnList = "shop_id,status,proposed_at"), @Index(name = "idx_shop_requester", columnList = "requester_id,id")})
public class ShopConversation {
    public enum Status { PENDING, CONFIRMED, DECLINED, CANCELLED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private VintageShop shop;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private User requester;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private User proposer;
    @Column(nullable = false, length = 150) private String title;
    @Column(length = 100) private String listingTitle;
    @Column(precision = 12, scale = 2) private BigDecimal askingPrice;
    @Column(length = 3) private String currency;
    private Instant proposedAt;
    @Column(length = 80) private String timeZone;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status = Status.PENDING;
    @Column(nullable = false) private long activity;
    @Version private long version;
    protected ShopConversation() { }
    ShopConversation(VintageShop shop, User requester, String title, ShopListing listing) {
        this.shop = shop; this.requester = requester; this.proposer = requester; this.title = title;
        if (listing != null) { listingTitle = listing.getTitle(); askingPrice = listing.getPrice(); currency = listing.getCurrency(); }
    }
    public Long getId() { return id; }
    public VintageShop getShop() { return shop; }
    public User getRequester() { return requester; }
    public User getProposer() { return proposer; }
    public String getTitle() { return title; }
    public String getListingTitle() { return listingTitle; }
    public BigDecimal getAskingPrice() { return askingPrice; }
    public String getCurrency() { return currency; }
    public Instant getProposedAt() { return proposedAt; }
    public String getTimeZone() { return timeZone; }
    public LocalDateTime getProposedLocal() { return proposedAt == null ? null : LocalDateTime.ofInstant(proposedAt, ZoneId.of(timeZone)); }
    public Status getStatus() { return status; }
    public long getVersion() { return version; }
    public boolean isOpen() { return status == Status.PENDING || status == Status.CONFIRMED; }
    public boolean isScheduled() { return proposedAt != null; }
    void propose(User actor, Instant time, String zone) { proposer = actor; proposedAt = time; timeZone = zone; }
    void resolve(Status status) { this.status = status; }
    void touch() { activity++; }
}
