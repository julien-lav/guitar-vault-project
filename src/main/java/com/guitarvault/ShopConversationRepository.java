package com.guitarvault;

import java.util.Optional;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface ShopConversationRepository extends JpaRepository<ShopConversation, Long> {
    @Query("select c from ShopConversation c where c.requester.id = :actor or c.shop.user.id = :actor order by c.id desc")
    Page<ShopConversation> inbox(Long actor, Pageable page);
    @Query("select c.shop.id from ShopConversation c where c.id = :id and (c.requester.id = :actor or c.shop.user.id = :actor)")
    Optional<Long> visibleShopId(Long id, Long actor);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ShopConversation c where c.id = :id and (c.requester.id = :actor or c.shop.user.id = :actor)")
    Optional<ShopConversation> findForUpdate(Long id, Long actor);
    @Query("select count(c) > 0 from ShopConversation c where c.shop.id = :shop and c.id <> :id and c.status = com.guitarvault.ShopConversation.Status.CONFIRMED and c.proposedAt > :start and c.proposedAt < :end")
    boolean overlaps(Long shop, Long id, Instant start, Instant end);
    @Query("select c from ShopConversation c where (c.requester.id = :actor or c.shop.user.id = :actor) and c.status = com.guitarvault.ShopConversation.Status.CONFIRMED and c.proposedAt > :now order by c.proposedAt, c.id")
    java.util.List<ShopConversation> upcoming(Long actor, Instant now, Pageable page);
    @Query("select c from ShopConversation c where (c.requester.id = :actor or c.shop.user.id = :actor) and c.status = com.guitarvault.ShopConversation.Status.PENDING and c.proposedAt > :now and c.proposer.id <> :actor order by c.id desc")
    java.util.List<ShopConversation> awaitingActor(Long actor, Instant now, Pageable page);

}
