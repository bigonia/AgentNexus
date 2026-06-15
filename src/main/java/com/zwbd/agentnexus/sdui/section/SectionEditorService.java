package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.capability.CapabilityContract;
import com.zwbd.agentnexus.sdui.capability.CapabilityContractService;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.protocol.catalog.FieldSpec;
import com.zwbd.agentnexus.sdui.protocol.catalog.SectionSpec;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SectionEditorService {

    private final CapabilityContractService contractService;
    private final DeviceCapabilityProjection capabilityProjection;

    public SectionEditorService(CapabilityContractService contractService,
                                DeviceCapabilityProjection capabilityProjection) {
        this.contractService = contractService;
        this.capabilityProjection = capabilityProjection;
    }

    public Map<String, Object> buildSectionEditor(String deviceId) {
        CapabilityContract contract = contractService.buildContract(deviceId);
        SectionRenderMode renderMode = SectionRenderMode.fromSizeClass(contract.display().sizeClass());
        List<Map<String, Object>> sectionTypes = capabilityProjection.sections(deviceId).stream()
                .map(spec -> toEditorSection(spec, renderMode))
                .toList();

        return Map.of(
                "deviceId", deviceId,
                "renderMode", renderMode.name().toLowerCase(),
                "sizeClass", contract.display().sizeClass(),
                "layouts", contract.display().layouts(),
                "sectionTypes", sectionTypes
        );
    }

    private Map<String, Object> toEditorSection(SectionSpec spec, SectionRenderMode renderMode) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", spec.type());
        entry.put("displayFields", toDisplayFields(spec, renderMode));
        entry.put("events", spec.events());
        entry.put("patchOps", spec.patchOps());
        return entry;
    }

    private List<Map<String, Object>> toDisplayFields(SectionSpec spec, SectionRenderMode renderMode) {
        return SectionTypeCatalog.get(spec.type())
                .map(def -> filterFields(spec.fields(), renderMode, def.compactHiddenFields(), ""))
                .orElseGet(() -> spec.fields().stream().map(this::toFieldMap).toList());
    }

    private List<Map<String, Object>> filterFields(List<FieldSpec> fields,
                                                   SectionRenderMode renderMode,
                                                   Set<String> compactHiddenFields,
                                                   String parentPath) {
        if (renderMode == SectionRenderMode.RICH || compactHiddenFields.isEmpty()) {
            return fields.stream().map(this::toFieldMap).toList();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (FieldSpec field : fields) {
            String fieldPath = parentPath.isEmpty() ? field.name() : parentPath + "[]." + field.name();
            if (compactHiddenFields.contains(fieldPath)) {
                continue;
            }

            List<Map<String, Object>> children = filterFields(
                    field.children(), renderMode, compactHiddenFields, field.name());
            result.add(Map.of(
                    "name", field.name(),
                    "type", field.type(),
                    "children", children
            ));
        }
        return result;
    }

    private Map<String, Object> toFieldMap(FieldSpec spec) {
        return Map.of(
                "name", spec.name(),
                "type", spec.type(),
                "children", spec.children().stream().map(this::toFieldMap).toList()
        );
    }

}
