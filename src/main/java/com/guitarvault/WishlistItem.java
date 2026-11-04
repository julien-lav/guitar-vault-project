package com.guitarvault;

import java.time.Instant;
import jakarta.persistence.*;

@Entity
@Table(name = "wishlist_items", indexes = @Index(name = "idx_wishlist_owner", columnList = "owner_id"))
public class WishlistItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, length = 100)
    private String brand;
    @Column(name = "manufacturing_year")
    private Integer year;
    @Column(length = 5000)
    private String notes;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected WishlistItem() { }

    public WishlistItem(User owner, String name, String brand, Integer year, String notes) {
        this.owner = owner;
        update(name, brand, year, notes);
    }

    public void update(String name, String brand, Integer year, String notes) {
        this.name = name;
        this.brand = brand;
        this.year = year;
        this.notes = notes;
    }

    public Long getId() { return id; }
    public User getOwner() { return owner; }
    public String getName() { return name; }
    public String getBrand() { return brand; }
    public Integer getYear() { return year; }
    public String getNotes() { return notes; }
    public Instant getCreatedAt() { return createdAt; }
}
