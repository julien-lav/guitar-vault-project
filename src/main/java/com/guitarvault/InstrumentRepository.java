package com.guitarvault;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select i from Instrument i where i.id = :id")
    java.util.Optional<Instrument> findForUpdate(Long id);
    List<Instrument> findByOwnerIdOrderByIdDesc(Long ownerId);
    interface PhotoPreview { Long getInstrumentId(); Long getPhotoId(); }
    @org.springframework.data.jpa.repository.Query("select p.instrument.id as instrumentId, min(p.id) as photoId from InstrumentPhoto p left join p.privateOwner author where p.instrument.owner.id = :actor and (author.id = :actor or author is null) and p.instrument.id in :ids group by p.instrument.id")
    java.util.List<PhotoPreview> previewPhotos(Long actor, java.util.List<Long> ids);

}
