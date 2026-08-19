package com.pronto.professionals.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@code @IdClass} for {@link ProfessionalSubService}'s genuinely composite {@code
 * (professionalId, subServiceId)} primary key. Mirrors {@code
 * favorites.entity.FavoriteId}'s exact existing pattern -- chosen over {@code @EmbeddedId} for
 * the same reason: {@link ProfessionalSubService} needs no separate embeddable value object
 * exposed through its API surface, just the two plain FK fields it already has for other
 * purposes. Field names/types must mirror {@link ProfessionalSubService}'s {@code @Id} fields
 * exactly, per the {@code @IdClass} contract.
 */
public class ProfessionalSubServiceId implements Serializable {

    private Long professionalId;
    private Long subServiceId;

    public ProfessionalSubServiceId() {
        // JPA
    }

    public ProfessionalSubServiceId(Long professionalId, Long subServiceId) {
        this.professionalId = professionalId;
        this.subServiceId = subServiceId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProfessionalSubServiceId that)) {
            return false;
        }
        return Objects.equals(professionalId, that.professionalId) && Objects.equals(subServiceId, that.subServiceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(professionalId, subServiceId);
    }
}
