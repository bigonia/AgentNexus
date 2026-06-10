package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.protocol.catalog.CommandSpec;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceProtocolCatalog;
import com.zwbd.agentnexus.sdui.protocol.catalog.FieldSpec;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class CommandSchemaRegistry {

    private final DeviceProtocolCatalog protocolCatalog;
    private final DeviceCapabilityProjection capabilityProjection;
    private final Map<String, Map<String, CommandSchema>> deviceSchemas = new ConcurrentHashMap<>();

    public CommandSchemaRegistry(DeviceProtocolCatalog protocolCatalog,
                                 DeviceCapabilityProjection capabilityProjection) {
        this.protocolCatalog = protocolCatalog;
        this.capabilityProjection = capabilityProjection;
    }

    public record CommandSchema(
            String command,
            String description,
            Map<String, FieldDef> fields
    ) {}

    public record FieldDef(
            String type,
            String label
    ) {}

    public void loadFromCapabilities(String deviceId, CapabilitySchema.CapabilitySnapshot caps) {
        Map<String, CommandSchema> schemas = new LinkedHashMap<>();
        for (CommandSpec command : capabilityProjection.commands(deviceId)) {
            Map<String, FieldDef> fields = new LinkedHashMap<>();
            for (FieldSpec field : command.params()) {
                fields.put(field.name(), new FieldDef(
                        field.type(), field.name()));
            }
            schemas.put(command.id(), new CommandSchema(command.id(), null, fields));
        }
        if (!schemas.isEmpty()) {
            deviceSchemas.put(deviceId, schemas);
            log.info("Loaded {} command schemas for device {}", schemas.size(), deviceId);
        }
    }

    public Optional<CommandSchema> getSchema(String deviceId, String command) {
        Map<String, CommandSchema> schemas = deviceSchemas.get(deviceId);
        if (schemas == null) return Optional.empty();
        return Optional.ofNullable(schemas.get(command));
    }

    public Map<String, CommandSchema> getAllSchemas(String deviceId) {
        return deviceSchemas.getOrDefault(deviceId, Collections.emptyMap());
    }

    public void clearDeviceSchemas(String deviceId) {
        deviceSchemas.remove(deviceId);
        log.info("Command schemas cleared for device {}", deviceId);
    }

    public List<String> validate(String deviceId, String command, Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Optional<CommandSchema> schemaOpt = getSchema(deviceId, command);
        if (schemaOpt.isEmpty()) return errors;

        CommandSchema schema = schemaOpt.get();
        for (var entry : schema.fields().entrySet()) {
            String name = entry.getKey();
            FieldDef field = entry.getValue();
            Object value = params != null ? params.get(name) : null;
            if (value == null) continue;

            switch (field.type()) {
                case "int" -> {
                    if (!(value instanceof Number)) {
                        errors.add(name + ": expected int, got " + value.getClass().getSimpleName());
                    }
                }
                case "string" -> {
                    if (!(value instanceof String)) {
                        errors.add(name + ": expected string, got " + value.getClass().getSimpleName());
                    }
                }
                case "bool" -> {
                    if (!(value instanceof Boolean)) {
                        errors.add(name + ": expected bool, got " + value.getClass().getSimpleName());
                    }
                }
                case "object" -> {
                    if (!(value instanceof Map<?, ?>)) {
                        errors.add(name + ": expected object, got " + value.getClass().getSimpleName());
                    }
                }
            }
        }
        return errors;
    }

    public Map<String, Object> applyDefaults(String deviceId, String command, Map<String, Object> params) {
        return params != null ? new LinkedHashMap<>(params) : new LinkedHashMap<>();
    }

}
