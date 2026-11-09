package com.guitarvault;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    @Column(length = 100)
    private String serialNumber;
    @Column(length = 200)
    private String previousOwner;
    @Column(length = 10000)
    private String history;
    @Column(length = 5000)
    private String notes;
    @ElementCollection
    @CollectionTable(name = "instrument_recordings", joinColumns = @JoinColumn(name = "instrument_id"))
    @OrderColumn(name = "entry_order")
    @Column(name = "description", length = 500)
    private List<String> recordings = new ArrayList<>();
    @ElementCollection
    @CollectionTable(name = "instrument_concerts", joinColumns = @JoinColumn(name = "instrument_id"))
    @OrderColumn(name = "entry_order")
    @Column(name = "description", length = 500)
    private List<String> concerts = new ArrayList<>();
    @OneToMany(mappedBy = "instrument", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<InstrumentPhoto> photos = new ArrayList<>();

    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }
    public String getPreviousOwner() { return previousOwner; }
    public void setPreviousOwner(String previousOwner) { this.previousOwner = previousOwner; }
    public String getHistory() { return history; }
    public void setHistory(String history) { this.history = history; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public List<String> getRecordings() { return recordings; }
    public List<String> getConcerts() { return concerts; }
    public List<InstrumentPhoto> getPhotos() { return photos; }

    protected Instrument() { }

    public Instrument(String name, String brand, Integer year, User owner, String status) {
        this.name = name;
        this.brand = brand;
        this.year = year;
        this.owner = owner;
        this.status = status;
    }

    void transferTo(User recipient) {
        owner = recipient;
        // These legacy free-text fields can contain financial or personal information.
        notes = null; history = null; previousOwner = null;
        if ("En vente".equals(status)) status = "Actif";
    }
    public Long getId() { return id; }
    public String getName() { return name; }
    public String getBrand() { return brand; }
    public Integer getYear() { return year; }
    public User getOwner() { return owner; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
