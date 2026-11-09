package com.guitarvault;

import java.util.List;
import org.springframework.data.jpa.repository.*;
public interface InstrumentDocumentRepository extends JpaRepository<InstrumentDocument, Long> {
    @Query("select d from InstrumentDocument d left join d.assessment a left join a.intervention i where d.uploader.id = :userId or (a is null and exists (select g.id from DocumentGrant g where g.document = d and g.recipient.id = :userId)) or (a is not null and d.visibility in :sharedScopes and i.requester.id = :userId) or (a is not null and d.visibility = :reportScope and d.instrument.owner.id = :userId) order by d.id desc")
    List<InstrumentDocument> libraryDocuments(Long userId, List<InstrumentDocument.Visibility> sharedScopes, InstrumentDocument.Visibility reportScope);
    long countByInstrumentIdAndUploaderId(Long instrumentId, Long uploaderId);
    List<InstrumentDocument> findByAssessmentIdInOrderByIdAsc(List<Long> ids);
    @Query("select d from InstrumentDocument d where d.assessment is null and (d.uploader.id = :userId or exists (select g.id from DocumentGrant g where g.document = d and g.recipient.id = :userId)) order by d.id desc")
    List<InstrumentDocument> personalDocuments(Long userId);
    @Query("select d from InstrumentDocument d where d.instrument.id = :instrumentId and d.assessment is null and (d.uploader.id = :userId or exists (select g.id from DocumentGrant g where g.document = d and g.recipient.id = :userId)) order by d.id desc")
    List<InstrumentDocument> transferableDocuments(Long instrumentId, Long userId);
    List<InstrumentDocument> findByUploaderIdAndAssessmentIsNotNullOrderByIdDesc(Long uploaderId);
}
