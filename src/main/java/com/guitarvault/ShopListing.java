package com.guitarvault;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "shop_listings", indexes = @Index(name = "idx_shop_listing", columnList = "shop_id,id"))
public class ShopListing {
    public enum Stock { AVAILABLE, RESERVED, SOLD }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private VintageShop shop;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private Instrument instrument;
    @Version private long version;
    @Column(nullable = false) private long activity;
    void touch() { activity++; }
    @Column(nullable = false, length = 100) private String title;
    @Column(nullable = false, length = 100) private String brand;
    @Column(name = "manufacturing_year") private Integer year;
    @Column(nullable = false, length = 3000) private String description;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal price;
    @Column(nullable = false, length = 3) private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Stock stock = Stock.AVAILABLE;
    @Column(nullable = false) private boolean published;
    @Column(nullable = false) private boolean photo;
    protected ShopListing() { }
    ShopListing(VintageShop shop, Instrument instrument) {
        this.shop = shop; this.instrument = instrument; title = instrument.getName(); brand = instrument.getBrand(); year = instrument.getYear();
    }
    public Long getId() { return id; }
    public VintageShop getShop() { return shop; }
    public Instrument getInstrument() { return instrument; }
    public long getVersion() { return version; }
    public String getTitle() { return title; }
    public String getBrand() { return brand; }
    public Integer getYear() { return year; }
    public String getDescription() { return description; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public Stock getStock() { return stock; }
    public boolean isPublished() { return published; }
    public boolean isPhoto() { return photo; }
    void photo(boolean photo) { this.photo = photo; }
    void update(String description, BigDecimal price, String currency, Stock stock, boolean published) {
        this.description = description; this.price = price; this.currency = currency; this.stock = stock; this.published = published;
    }
}
