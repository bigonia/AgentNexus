package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.capability.CapabilityCatalog;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class CommandSchemaRegistry {

    private final CapabilityCatalog catalog;
    private final Map<String, Map<String, CommandSchema>> deviceSchemas = new ConcurrentHashMap<>();

    public CommandSchemaRegistry(CapabilityCatalog catalog) {
        this.catalog = catalog;
    }

    public record CommandSchema(
            String command,
            String description,
            Map<String, FieldDef> fields
    ) {}

    public record FieldDef(
            String type,
            Integer min,
            Integer max,
            Object defaultValue,
            List<String> values,
            boolean required,
            String label
    ) {}

    public void loadFromCapabilities(String deviceId, CapabilitySchema.CapabilitySnapshot caps) {
        Map<String, CommandSchema> schemas = new LinkedHashMap<>();
        for (String outputName : caps.outputs()) {
            CapabilityCatalog.OutputDef outputDef = catalog.getOutput(outputName).orElse(null);
            if (outputDef == null || outputDef.commands() == null) continue;
            for (CapabilityCatalog.CommandDef cmdDef : outputDef.commands().values()) {
                if (cmdDef.internal()) continue;
                Map<String, FieldDef> fields = new LinkedHashMap<>();
                for (var entry : cmdDef.params().entrySet()) {
                    CapabilityCatalog.FieldSchema fs = entry.getValue();
                    fields.put(entry.getKey(), new FieldDef(
                            fs.type(), fs.min(), fs.max(), fs.defaultValue(),
                            fs.values(), fs.required(),
                            fs.label() != null ? fs.label() : entry.getKey()));
                }
                schemas.put(cmdDef.command(), new CommandSchema(cmdDef.command(), null, fields));
            }
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
        Set<String> provided = params != null ? params.keySet() : Collections.emptySet();

        for (var entry : schema.fields().entrySet()) {
            String name = entry.getKey();
            FieldDef field = entry.getValue();
            Object value = params != null ? params.get(name) : null;

            if (value == null && field.required() && !provided.contains(name)) {
                errors.add(name + ": required field missing");
                continue;
            }
            if (value == null) continue;

            switch (field.type()) {
                case "int" -> {
                    if (!(value instanceof Number)) {
                        errors.add(name + ": expected int, got " + value.getClass().getSimpleName());
                    } else {
                        int v = ((Number) value).intValue();
                        if (field.min() != null && v < field.min())
                            errors.add(name + ": min is " + field.min() + ", got " + v);
                        if (field.max() != null && v > field.max())
                            errors.add(name + ": max is " + field.max() + ", got " + v);
                    }
                }
                case "enum" -> {
                    if (field.values() != null && !field.values().contains(String.valueOf(value))) {
                        errors.add(name + ": expected one of " + field.values() + ", got " + value);
                    }
                }
            }
        }
        return errors;
    }

    public Map<String, Object> applyDefaults(String deviceId, String command, Map<String, Object> params) {
        Optional<CommandSchema> schemaOpt = getSchema(deviceId, command);
        if (schemaOpt.isEmpty()) return params != null ? new LinkedHashMap<>(params) : new LinkedHashMap<>();

        Map<String, Object> result = new LinkedHashMap<>(params != null ? params : new LinkedHashMap<>());
        for (var entry : schemaOpt.get().fields().entrySet()) {
            String name = entry.getKey();
            FieldDef field = entry.getValue();
            if (!result.containsKey(name) && field.defaultValue() != null) {
                result.put(name, field.defaultValue());
            }
        }
        return result;
    }

}
