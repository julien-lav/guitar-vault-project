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
    @Column(nullable = false)
    private String contentType;
    @Lob @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    private byte[] data;

    protected InstrumentPhoto() { }
    public InstrumentPhoto(Instrument instrument, String contentType, byte[] data) {
        this.instrument = instrument;
        this.contentType = contentType;
        this.data = data;
    }
    public Long getId() { return id; }
    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
}
