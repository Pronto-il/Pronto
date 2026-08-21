package com.pronto.issues.entity.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.ai.dto.CategoryCandidate;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Persists the classification's candidate list as a JSON array in a {@code TEXT} column.
 *
 * <p>Same rationale as {@link StringListConverter} (own mapper, forgiving reads). Unknown
 * properties are ignored on read so an older row written before a field existed still
 * deserialises — this column is pure telemetry and must never be the reason an issue cannot
 * be loaded.
 */
@Converter
public class CategoryCandidateListConverter implements AttributeConverter<List<CategoryCandidate>, String> {

    private static final Logger log = LoggerFactory.getLogger(CategoryCandidateListConverter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<List<CategoryCandidate>> TYPE = new TypeReference<>() {
    };
    private static final String EMPTY_JSON_ARRAY = "[]";

    @Override
    public String convertToDatabaseColumn(List<CategoryCandidate> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return EMPTY_JSON_ARRAY;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            log.warn("issue.classification.serialize.failed field=candidates size={}", attribute.size(), e);
            return EMPTY_JSON_ARRAY;
        }
    }

    @Override
    public List<CategoryCandidate> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            List<CategoryCandidate> values = MAPPER.readValue(dbData, TYPE);
            return values == null ? List.of() : List.copyOf(values);
        } catch (Exception e) {
            log.warn("issue.classification.deserialize.failed field=candidates", e);
            return List.of();
        }
    }
}
