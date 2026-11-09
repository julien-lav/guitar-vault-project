package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;

/** An accepted transfer grants access to exactly its selected documents. */
@Entity
@Table(name = "document_grants", uniqueConstraints = @UniqueConstraint(columnNames = {"document_id", "recipient_id"}))
public class DocumentGrant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private InstrumentDocument document;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private User recipient;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false) private InstrumentTransfer transfer;
    @Column(nullable = false, updatable = false) private Instant grantedAt = Instant.now();
    protected DocumentGrant() { }
    public DocumentGrant(InstrumentDocument document, User recipient, InstrumentTransfer transfer) {
        this.document = document; this.recipient = recipient; this.transfer = transfer;
    }
}
