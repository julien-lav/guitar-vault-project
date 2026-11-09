package com.guitarvault;

import jakarta.persistence.*;

@Entity
@Table(name = "instrument_photos")
public class InstrumentPhoto {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_id", nullable = false)
    private Instrument instrument;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "private_owner_id")
    private User privateOwner;
    @Column(nullable = false)
    private String contentType;
    @Lob @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    private byte[] data;

    protected InstrumentPhoto() { }
    public InstrumentPhoto(Instrument instrument, String contentType, byte[] data) {
        this.instrument = instrument;
        this.privateOwner = instrument.getOwner();
        this.contentType = contentType;
        this.data = data;
    }
    void preservePrivateOwner(User owner) { if (privateOwner == null) privateOwner = owner; }
    public boolean visibleTo(User actor) {
        return (privateOwner == null ? instrument.getOwner() : privateOwner).getId().equals(actor.getId());
    }
    public Long getId() { return id; }
    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
}
