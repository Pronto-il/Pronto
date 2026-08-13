package com.pronto.notifications.repository;

import com.pronto.notifications.entity.Notification;
import com.pronto.notifications.entity.NotificationChannel;
import com.pronto.notifications.entity.NotificationDeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * See {@code docs/architecture/api-contract-notifications.md} §3.1-3.3 (in-app feed
 * queries) and §4.4 (email-dispatch queue query). {@code channel = IN_APP} filtering on the
 * feed queries is deliberate — {@code EMAIL}-channel rows are an internal dispatch-pipeline
 * concern, never surfaced through {@code GET /api/notifications} (§3.1).
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** §3.1 step 2, {@code unreadOnly=false} branch. Matches {@code idx_notifications_user_created}. */
    List<Notification> findByUserIdAndChannelOrderByCreatedAtDesc(Long userId, NotificationChannel channel);

    /** §3.1 step 2, {@code unreadOnly=true} branch. */
    List<Notification> findByUserIdAndChannelAndReadAtIsNullOrderByCreatedAtDesc(Long userId, NotificationChannel channel);

    /** §3.1 step 3 — always computed regardless of the {@code unreadOnly} filter. */
    long countByUserIdAndChannelAndReadAtIsNull(Long userId, NotificationChannel channel);

    /** §3.3 — "clear the bell." Returns the affected-row count. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.userId = :userId "
            + "AND n.channel = com.pronto.notifications.entity.NotificationChannel.IN_APP AND n.readAt IS NULL")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * §4.4 — {@code EmailDispatchJob}'s "what still needs sending" query, uses
     * {@code idx_notifications_channel_status}. Batch size (50) matches the contract doc's
     * sketch exactly.
     */
    List<Notification> findFirst50ByChannelAndDeliveryStatusOrderByCreatedAtAsc(
            NotificationChannel channel, NotificationDeliveryStatus deliveryStatus);
}
