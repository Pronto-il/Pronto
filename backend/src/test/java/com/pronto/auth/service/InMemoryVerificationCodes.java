package com.pronto.auth.service;

import com.pronto.auth.entity.OtpPurpose;
import com.pronto.auth.entity.VerificationCode;
import com.pronto.auth.repository.VerificationCodeRepository;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;

/**
 * A working in-memory {@link VerificationCodeRepository}, for the OTP tests.
 *
 * <p><b>Why not plain Mockito stubs.</b> The rules under test — single use, the attempt ceiling,
 * resend invalidating its predecessor — are all <em>stateful</em>: they are about what the second
 * call sees after the first one. A {@code when(...).thenReturn(...)} stub has no memory, so a suite
 * built on one can only ever assert that a method was called, never that the rule it implements
 * actually holds. This fake replays the three conditional UPDATE statements with the same
 * semantics the JPQL has, including their return values, so a test that says "the sixth guess is
 * refused" is really exercising a counter.
 *
 * <p>Reflection is used to advance {@code attempts}/{@code consumedAt}: {@link VerificationCode}
 * deliberately exposes no setters for either, because in production only those UPDATE statements
 * may move them.
 */
final class InMemoryVerificationCodes {

    private final Map<UUID, VerificationCode> byChallengeId = new HashMap<>();
    private final Map<Long, VerificationCode> byId = new HashMap<>();
    private long nextId = 1L;

    private final VerificationCodeRepository repository = Mockito.mock(VerificationCodeRepository.class);

    VerificationCodeRepository repository() {
        return repository;
    }

    /** Every challenge ever saved, oldest first. */
    List<VerificationCode> all() {
        return byId.values().stream()
                .sorted(Comparator.comparing(VerificationCode::getId))
                .toList();
    }

    VerificationCode newest() {
        List<VerificationCode> all = all();
        return all.get(all.size() - 1);
    }

    /** Forces a challenge to look expired, without waiting out its TTL. */
    static void expire(VerificationCode challenge) {
        setField(challenge, "expiresAt", Instant.now().minusSeconds(1));
    }

    /** Backdates issuance, so cooldown/hourly-window rules can be tested without sleeping. */
    static void backdate(VerificationCode challenge, long seconds) {
        setField(challenge, "createdAt", challenge.getCreatedAt().minusSeconds(seconds));
    }

    InMemoryVerificationCodes() {
        Mockito.lenient().when(repository.save(any(VerificationCode.class))).thenAnswer(inv -> {
            VerificationCode challenge = inv.getArgument(0);
            if (challenge.getId() == null) {
                setField(challenge, "id", nextId++);
            }
            if (challenge.getCreatedAt() == null) {
                setField(challenge, "createdAt", Instant.now());
            }
            byId.put(challenge.getId(), challenge);
            byChallengeId.put(challenge.getChallengeId(), challenge);
            return challenge;
        });

        Mockito.lenient().when(repository.findByChallengeId(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(byChallengeId.get(inv.<UUID>getArgument(0))));

        Mockito.lenient().when(repository.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(
                        anyLong(), any(OtpPurpose.class)))
                .thenAnswer(inv -> matching(inv.getArgument(0), inv.getArgument(1)).stream()
                        .max(Comparator.comparing(VerificationCode::getCreatedAt)));

        Mockito.lenient().when(repository.countIssuedSince(anyLong(), any(OtpPurpose.class), any(Instant.class)))
                .thenAnswer(inv -> {
                    Instant since = inv.getArgument(2);
                    return matching(inv.getArgument(0), inv.getArgument(1)).stream()
                            .filter(c -> !c.getCreatedAt().isBefore(since))
                            .count();
                });

        // The three conditional UPDATEs. Each returns the row count its JPQL would.
        Mockito.lenient().when(repository.registerFailedAttempt(anyLong(), anyShort())).thenAnswer(inv -> {
            VerificationCode challenge = byId.get(inv.<Long>getArgument(0));
            short max = inv.getArgument(1);
            if (challenge == null || challenge.getConsumedAt() != null || challenge.getAttempts() >= max) {
                return 0;
            }
            setField(challenge, "attempts", (short) (challenge.getAttempts() + 1));
            return 1;
        });

        Mockito.lenient().when(repository.consume(anyLong(), any(Instant.class))).thenAnswer(inv -> {
            VerificationCode challenge = byId.get(inv.<Long>getArgument(0));
            if (challenge == null || challenge.getConsumedAt() != null) {
                return 0;
            }
            setField(challenge, "consumedAt", inv.getArgument(1));
            return 1;
        });

        // Same statement plus the expiry predicate that closes the check/write gap.
        Mockito.lenient().when(repository.consumeIfValid(anyLong(), any(Instant.class))).thenAnswer(inv -> {
            VerificationCode challenge = byId.get(inv.<Long>getArgument(0));
            Instant now = inv.getArgument(1);
            if (challenge == null || challenge.getConsumedAt() != null
                    || !challenge.getExpiresAt().isAfter(now)) {
                return 0;
            }
            setField(challenge, "consumedAt", now);
            return 1;
        });

        Mockito.lenient().when(repository.supersedeOtherOpenChallenges(
                        anyLong(), any(OtpPurpose.class), anyLong(), any(Instant.class)))
                .thenAnswer(inv -> {
                    Long keepId = inv.getArgument(2);
                    int affected = 0;
                    for (VerificationCode challenge : matching(inv.getArgument(0), inv.getArgument(1))) {
                        if (challenge.getConsumedAt() == null && !challenge.getId().equals(keepId)) {
                            setField(challenge, "consumedAt", inv.getArgument(3));
                            affected++;
                        }
                    }
                    return affected;
                });

        Mockito.lenient().when(repository.invalidateOpenChallenges(
                        anyLong(), any(OtpPurpose.class), any(Instant.class)))
                .thenAnswer(inv -> {
                    int affected = 0;
                    for (VerificationCode challenge : matching(inv.getArgument(0), inv.getArgument(1))) {
                        if (challenge.getConsumedAt() == null) {
                            setField(challenge, "consumedAt", inv.getArgument(2));
                            affected++;
                        }
                    }
                    return affected;
                });
    }

    private List<VerificationCode> matching(Long userId, OtpPurpose purpose) {
        List<VerificationCode> found = new ArrayList<>();
        for (VerificationCode challenge : byId.values()) {
            if (challenge.getUserId().equals(userId) && challenge.getPurpose() == purpose) {
                found.add(challenge);
            }
        }
        return found;
    }

    static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
