package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "instrument_transfers", indexes = @Index(name = "idx_transfer_instrument", columnList = "instrument_id"))
public class InstrumentTransfer {
    public enum Status { PENDING, ACCEPTED, DECLINED, CANCELLED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private Instrument instrument;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private User sender;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private User recipient;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status = Status.PENDING;
    @Column(nullable = false, length = 100) private String instrumentName;
    @Column(nullable = false, length = 100) private String brand;
    private Integer manufacturingYear;
    @Column(length = 100) private String serialNumber;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    private Instant resolvedAt;
    @ManyToMany
    @JoinTable(name = "transfer_documents", joinColumns = @JoinColumn(name = "transfer_id"), inverseJoinColumns = @JoinColumn(name = "document_id"))
    private java.util.List<InstrumentDocument> documents = new java.util.ArrayList<>();
    public java.util.List<InstrumentDocument> getDocuments() { return java.util.Collections.unmodifiableList(documents); }
    void selectDocuments(java.util.List<InstrumentDocument> selected) { documents.addAll(selected); }
    protected InstrumentTransfer() { }
    public InstrumentTransfer(Instrument instrument, User sender, User recipient) {
        this.instrument = instrument; this.sender = sender; this.recipient = recipient;
        instrumentName = instrument.getName(); brand = instrument.getBrand();
        manufacturingYear = instrument.getYear(); serialNumber = instrument.getSerialNumber();
    }
    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public User getSender() { return sender; }
    public User getRecipient() { return recipient; }
    public Status getStatus() { return status; }
    public String getInstrumentName() { return instrumentName; }
    public String getBrand() { return brand; }
    public Integer getManufacturingYear() { return manufacturingYear; }
    public String getSerialNumber() { return serialNumber; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    void resolve(Status status, Instant at) { this.status = status; resolvedAt = at; }
}
