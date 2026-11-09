package com.guitarvault;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
public interface DocumentLibraryRepository extends JpaRepository<DocumentLibraryEntry, Long> {
    Optional<DocumentLibraryEntry> findByDocument_IdAndReader_Id(Long documentId, Long readerId);
    List<DocumentLibraryEntry> findByReaderId(Long readerId);
    boolean existsByDocument_IdAndReader_IdAndArchivedTrue(Long documentId, Long readerId);
    List<DocumentLibraryEntry> findByDocument_Id(Long documentId);
}
