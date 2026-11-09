package com.guitarvault;

import jakarta.persistence.*;
@Entity
@Table(name = "document_contents")
public class DocumentContent {
    @Id private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @MapsId @JoinColumn(name = "id") private InstrumentDocument document;
    @Lob @Column(nullable = false) private byte[] data;
    protected DocumentContent() { }
    public DocumentContent(InstrumentDocument document, byte[] data) { this.document = document; this.data = data; }
    public byte[] getData() { return data; }
}
