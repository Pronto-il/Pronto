package com.pronto.issues.entity.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Persists the Professional Brief's list-valued fields (observations, causes, tools, parts,
 * safety notes) as a JSON array in a {@code TEXT} column.
 *
 * <p>Uses its own {@link ObjectMapper} rather than Spring's: JPA converters are instantiated
 * by Hibernate, not the container, so an injected one is not available here. The instance is
 * stateless and thread-safe.
 *
 * <p>Reading is deliberately forgiving — a row that somehow contains unparseable text yields
 * an empty list and a warning rather than breaking every read of the owning issue. The brief
 * is advisory; a corrupted advisory field must not take a booking screen down.
 */
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final Logger log = LoggerFactory.getLogger(StringListConverter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {
    };
    private static final String EMPTY_JSON_ARRAY = "[]";

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return EMPTY_JSON_ARRAY;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            log.warn("issue.brief.serialize.failed field=stringList size={}", attribute.size(), e);
            return EMPTY_JSON_ARRAY;
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = MAPPER.readValue(dbData, TYPE);
            return values == null ? List.of() : List.copyOf(values);
        } catch (Exception e) {
            log.warn("issue.brief.deserialize.failed field=stringList", e);
            return List.of();
        }
    }
}
