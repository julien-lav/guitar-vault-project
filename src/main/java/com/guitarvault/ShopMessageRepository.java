package com.guitarvault;
public interface ShopMessageRepository extends org.springframework.data.jpa.repository.JpaRepository<ShopMessage, Long> {
    java.util.List<ShopMessage> findByConversationIdOrderByIdAsc(Long conversationId);
}
