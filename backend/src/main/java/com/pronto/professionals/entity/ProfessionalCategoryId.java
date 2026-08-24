package com.pronto.professionals.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@code @IdClass} for {@link ProfessionalCategory}'s composite
 * {@code (professionalId, categoryId)} primary key. Same pattern, and the same reasoning for
 * {@code @IdClass} over {@code @EmbeddedId}, as {@link ProfessionalSubServiceId}.
 */
public class ProfessionalCategoryId implements Serializable {

    private Long professionalId;
    private Long categoryId;

    public ProfessionalCategoryId() {
        // JPA
    }

    public ProfessionalCategoryId(Long professionalId, Long categoryId) {
        this.professionalId = professionalId;
        this.categoryId = categoryId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProfessionalCategoryId that)) {
            return false;
        }
        return Objects.equals(professionalId, that.professionalId) && Objects.equals(categoryId, that.categoryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(professionalId, categoryId);
    }
}
