package com.zwbd.agentnexus.common.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * JPA converter for Map<String, String> to JSON string conversion
 * Handles the conversion between Java Map objects and JSON strings for database storage
 */
@Converter
@Slf4j
public class MapToJsonConverter implements AttributeConverter<Map<String, String>, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Convert Map to JSON string for database storage
     *
     * @param attribute the Map to convert
     * @return JSON string representation
     */
    @Override
    public String convertToDatabaseColumn(Map<String, String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("Error converting Map to JSON string", e);
            throw new RuntimeException("Error converting Map to JSON string", e);
        }
    }

    /**
     * Convert JSON string from database to Map
     *
     * @param dbData the JSON string from database
     * @return Map<String, String> object
     */
    @Override
    public Map<String, String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.trim().isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(dbData, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            log.error("Error converting JSON string to Map", e);
            throw new RuntimeException("Error converting JSON string to Map", e);
        }
    }
}