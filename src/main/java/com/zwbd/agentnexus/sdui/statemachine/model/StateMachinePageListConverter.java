package com.zwbd.agentnexus.sdui.statemachine.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Converter
public class StateMachinePageListConverter implements AttributeConverter<List<Map<String, Object>>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> LIST_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<Map<String, Object>> attribute) {
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute != null ? attribute : List.of());
        } catch (Exception e) {
            log.error("Failed to serialize state machine pages JSON", e);
            throw new IllegalArgumentException("invalid state machine pages json", e);
        }
    }

    @Override
    public List<Map<String, Object>> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> list = OBJECT_MAPPER.readValue(dbData, LIST_TYPE);
            // Ensure all maps are mutable LinkedHashMaps
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> item : list) {
                result.add(new LinkedHashMap<>(item));
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to deserialize state machine pages JSON", e);
            return new ArrayList<>();
        }
    }
}
