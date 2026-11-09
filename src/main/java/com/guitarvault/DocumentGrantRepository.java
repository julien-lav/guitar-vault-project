package com.guitarvault;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DocumentGrantRepository extends JpaRepository<DocumentGrant, Long> {
    boolean existsByDocumentId(Long documentId);
    @org.springframework.data.jpa.repository.Query("select distinct g.document.id from DocumentGrant g where g.document.id in :ids")
    java.util.List<Long> grantedDocumentIds(java.util.List<Long> ids);
    boolean existsByDocumentIdAndRecipientId(Long documentId, Long recipientId);
}
