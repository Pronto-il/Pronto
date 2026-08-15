package com.pronto.favorites.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@code @IdClass} for {@link Favorite}'s genuinely composite {@code (customerId,
 * professionalId)} primary key. Chosen over {@code @EmbeddedId} as the simpler of the two
 * standard JPA composite-key mechanisms here — {@link Favorite} needs no separate embeddable
 * value object exposed through its API surface, just the two plain FK fields it already has
 * for other purposes; {@code @IdClass} lets those same fields double as the identifier
 * without introducing a wrapper type. Field names/types must mirror {@link Favorite}'s
 * {@code @Id} fields exactly, per the {@code @IdClass} contract.
 */
public class FavoriteId implements Serializable {

    private Long customerId;
    private Long professionalId;

    public FavoriteId() {
        // JPA
    }

    public FavoriteId(Long customerId, Long professionalId) {
        this.customerId = customerId;
        this.professionalId = professionalId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FavoriteId that)) {
            return false;
        }
        return Objects.equals(customerId, that.customerId) && Objects.equals(professionalId, that.professionalId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(customerId, professionalId);
    }
}
