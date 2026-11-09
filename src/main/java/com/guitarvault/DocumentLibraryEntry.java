package com.guitarvault;

import jakarta.persistence.*;

/** Personal labels and archive state never rewrite the passport's original metadata. */
@Entity
@Table(name = "document_library_entries", uniqueConstraints = @UniqueConstraint(columnNames = {"document_id", "reader_id"}))
public class DocumentLibraryEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private InstrumentDocument document;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "reader_id", nullable = false) private User reader;
    @Version private long version;
    @Column(length = 150) private String title;
    @Enumerated(EnumType.STRING) @Column(length = 20) private InstrumentDocument.Kind kind;
    @Column(nullable = false) private boolean archived;
    protected DocumentLibraryEntry() { }
    public DocumentLibraryEntry(InstrumentDocument document, User reader) { this.document = document; this.reader = reader; }
    public Long getDocumentId() { return document.getId(); }
    public long getVersion() { return version; }
    public String getTitle() { return title; }
    public InstrumentDocument.Kind getKind() { return kind; }
    public boolean isArchived() { return archived; }
    void label(String title, InstrumentDocument.Kind kind) { this.title = title; this.kind = kind; }
    void archive(boolean value) { archived = value; }
}
