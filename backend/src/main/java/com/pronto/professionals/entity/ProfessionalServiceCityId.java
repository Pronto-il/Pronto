package com.pronto.professionals.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@code @IdClass} for {@link ProfessionalServiceCity}'s composite
 * {@code (professionalId, cityId)} primary key. Same pattern, and the same reasoning for
 * {@code @IdClass} over {@code @EmbeddedId}, as {@link ProfessionalSubServiceId}.
 */
public class ProfessionalServiceCityId implements Serializable {

    private Long professionalId;
    private Long cityId;

    public ProfessionalServiceCityId() {
        // JPA
    }

    public ProfessionalServiceCityId(Long professionalId, Long cityId) {
        this.professionalId = professionalId;
        this.cityId = cityId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProfessionalServiceCityId that)) {
            return false;
        }
        return Objects.equals(professionalId, that.professionalId) && Objects.equals(cityId, that.cityId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(professionalId, cityId);
    }
}
