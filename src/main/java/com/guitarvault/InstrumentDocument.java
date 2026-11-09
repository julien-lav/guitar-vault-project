package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;

/** Metadata only: file bytes are loaded separately, after authorization. */
@Entity
@Table(name = "instrument_documents", indexes = @Index(name = "idx_document_instrument", columnList = "instrument_id"))
public class InstrumentDocument {
    public enum Kind { INVOICE, RECEIPT, CERTIFICATE, APPRAISAL, OTHER, EVIDENCE }
    public enum Visibility { PRIVATE, REQUEST, REPORT }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private Instrument instrument;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private User uploader;
    @ManyToOne(fetch = FetchType.LAZY) private LuthierAssessment assessment;
    @Column(nullable = false, length = 150) private String title;
    @Column(nullable = false, length = 120) private String filename;
    @Column(nullable = false, length = 50) private String contentType;
    @Column(nullable = false) private long byteSize;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Kind kind;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Visibility visibility;
    @Column(nullable = false, updatable = false) private Instant uploadedAt = Instant.now();
    protected InstrumentDocument() { }
    public InstrumentDocument(Instrument instrument, User uploader, LuthierAssessment assessment, String title,
            String filename, String contentType, long byteSize, Kind kind, Visibility visibility) {
        this.instrument = instrument; this.uploader = uploader; this.assessment = assessment; this.title = title;
        this.filename = filename; this.contentType = contentType; this.byteSize = byteSize; this.kind = kind; this.visibility = visibility;
    }
    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public User getUploader() { return uploader; }
    public LuthierAssessment getAssessment() { return assessment; }
    public String getTitle() { return title; }
    public String getFilename() { return filename; }
    public String getContentType() { return contentType; }
    public long getByteSize() { return byteSize; }
    public Kind getKind() { return kind; }
    public Visibility getVisibility() { return visibility; }
    public Instant getUploadedAt() { return uploadedAt; }
}
