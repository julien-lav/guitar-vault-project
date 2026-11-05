package com.guitarvault;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Page<Notification> findByRecipientIdOrderByIdDesc(Long recipientId, Pageable pageable);
    Page<Notification> findByRecipientIdAndReadAtIsNullOrderByIdDesc(Long recipientId, Pageable pageable);
    long countByRecipientIdAndReadAtIsNull(Long recipientId);
    @Query("select count(n) from Notification n where lower(n.recipient.email) = lower(:email) and n.recipient.enabled = true and n.recipient.suspended = false and n.readAt is null")
    long unreadForEmail(String email);
    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);
    @Modifying(flushAutomatically = true)
    @Query("update Notification n set n.readAt = coalesce(n.readAt, :now) where n.id = :id and n.recipient.id = :recipientId")
    int markRead(Long id, Long recipientId, Instant now);
    @Modifying(flushAutomatically = true)
    @Query("update Notification n set n.readAt = :now where n.recipient.id = :recipientId and n.readAt is null")
    int markAllRead(Long recipientId, Instant now);
}
