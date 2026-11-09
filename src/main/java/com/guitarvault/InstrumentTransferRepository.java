package com.guitarvault;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
public interface InstrumentTransferRepository extends JpaRepository<InstrumentTransfer, Long> {
    boolean existsByDocumentsId(Long documentId);
    @Query("select distinct d.id from InstrumentTransfer t join t.documents d where d.id in :ids")
    List<Long> referencedDocumentIds(List<Long> ids);
    List<InstrumentTransfer> findBySenderIdOrRecipientIdOrderByIdDesc(Long senderId, Long recipientId);
    boolean existsByInstrumentIdAndStatus(Long instrumentId, InstrumentTransfer.Status status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from InstrumentTransfer t where t.id = :id")
    Optional<InstrumentTransfer> findForUpdate(Long id);
}
