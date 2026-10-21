package com.guitarvault;

import java.time.Instant;

import jakarta.persistence.*;

@Entity
@Table(name = "instruments", indexes = @Index(name = "idx_instruments_owner", columnList = "owner_id"))
public class Instrument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, length = 100)
    private String brand;
    @Column(name = "manufacturing_year")
    private Integer year;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;
    @Column(nullable = false, length = 30)
    private String status;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Instrument() { }

    public Instrument(String name, String brand, Integer year, User owner, String status) {
        this.name = name;
        this.brand = brand;
        this.year = year;
        this.owner = owner;
        this.status = status;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getBrand() { return brand; }
    public Integer getYear() { return year; }
    public User getOwner() { return owner; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
