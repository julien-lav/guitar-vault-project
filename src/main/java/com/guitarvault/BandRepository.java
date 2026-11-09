package com.guitarvault;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BandRepository extends JpaRepository<Band, Long> {
    boolean existsByIdAndCreatorId(Long id, Long creatorId);
    @org.springframework.data.jpa.repository.Query("""
            select distinct b from Band b left join b.genres g
            where b.moderationHidden = false and (:genre = '' or lower(g) = :genre
                or (b.genres is empty and lower(b.genre) = :genre))
              and (:region = '' or lower(b.region) like :region escape '!')
              and (:city = '' or lower(b.city) like :city escape '!')
            order by b.id desc
            """)
    List<Band> search(String genre, String region, String city);
    @org.springframework.data.jpa.repository.Query("select distinct g from Band b join b.genres g where b.moderationHidden = false order by g")
    List<String> genreOptions();
    @org.springframework.data.jpa.repository.Query("select distinct b.genre from Band b where b.moderationHidden = false and b.genres is empty and b.genre is not null and b.genre <> '' order by b.genre")
    List<String> legacyGenreOptions();
    @org.springframework.data.jpa.repository.Query("select distinct b.region from Band b where b.moderationHidden = false and b.region is not null and b.region <> '' order by b.region")
    List<String> regionOptions();
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select b from Band b where b.id = :id")
    java.util.Optional<Band> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
    @org.springframework.data.jpa.repository.Query("select b from Band b where b.creator.id = :userId and b.moderationHidden = false order by b.name")
    List<Band> findByCreatorIdOrderByNameAsc(Long userId);
    List<Band> findTop3ByMembersIdOrderByNameAsc(Long userId);
    long countByMembersId(Long userId);
    @org.springframework.data.jpa.repository.Query("select b from Band b where b.moderationHidden = false order by b.id desc")
    List<Band> findAllByOrderByIdDesc();
    @org.springframework.data.jpa.repository.Query("select b from Band b join b.members m where m.id = :userId and b.moderationHidden = false order by b.name")
    List<Band> findByMembersIdOrderByNameAsc(Long userId);
}
