package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.service.SduiDeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/sdui/section")
@RequiredArgsConstructor
public class SectionTriggerController {

    private final SectionAutoUpdateScheduler autoUpdateScheduler;
    private final SduiDeviceService deviceService;

    @GetMapping("/presets")
    public ApiResponse<Map<String, Object>> listPresets() {
        List<Map<String, Object>> presets = new ArrayList<>();
        for (SectionAutoUpdateScheduler.PresetMeta meta : autoUpdateScheduler.getPresetDetails()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", meta.name());
            p.put("label", meta.label());
            p.put("description", meta.description());
            p.put("layout", meta.layout());
            p.put("sectionCount", meta.sectionCount());
            p.put("sectionTypes", meta.sectionTypes());
            presets.add(p);
        }
        return ApiResponse.ok(Map.of(
                "presets", presets,
                "availableLayouts", SectionLayout.availableLayouts()
        ));
    }

    @GetMapping("/auto/status")
    public ApiResponse<List<SectionAutoUpdateScheduler.AutoUpdateStatus>> autoStatus() {
        return ApiResponse.ok(autoUpdateScheduler.listStatus());
    }

    @GetMapping("/devices")
    public ApiResponse<List<?>> listDevicesWithSectionSupport() {
        // TODO: filter by section capability once query supports it
        return ApiResponse.ok(deviceService.listDevices());
    }

}
