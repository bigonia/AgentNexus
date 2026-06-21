package com.zwbd.agentnexus.sdui.statemachine;

import com.zwbd.agentnexus.sdui.section.SectionDataCodec;
import com.zwbd.agentnexus.sdui.section.SectionOrchestrationService;
import com.zwbd.agentnexus.sdui.section.SectionPatch;
import com.zwbd.agentnexus.sdui.section.SectionScene;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateMachineProjectionServiceTest {

    @Test
    void projectsPageAsSceneToBoundDevices() {
        CapturingOrchestration orchestration = new CapturingOrchestration();
        StateMachineProjectionService service = new StateMachineProjectionService(orchestration, new SectionDataCodec());

        List<Map<String, Object>> results = service.projectScene(List.of(
                page("main", List.of("device-a"), "Hello", 10)
        ));

        assertTrue((Boolean) results.get(0).get("sent"));
        assertEquals("device-a", orchestration.sceneDeviceId);
        assertEquals("main", orchestration.scene.pageId());
        assertEquals(1, orchestration.scene.sections().size());
    }

    @Test
    void projectsSmallSectionChangeAsPatch() {
        CapturingOrchestration orchestration = new CapturingOrchestration();
        StateMachineProjectionService service = new StateMachineProjectionService(orchestration, new SectionDataCodec());

        List<Map<String, Object>> results = service.projectTransition(
                List.of(page("main", List.of("device-a"), "Hello", 10)),
                List.of(page("main", List.of("device-a"), "Done", 100))
        );

        assertTrue((Boolean) results.get(0).get("sent"));
        assertEquals("device-a", orchestration.patchDeviceId);
        assertEquals(1, orchestration.patch.patches().size());
        assertEquals("update", orchestration.patch.patches().get(0).op());
    }

    @Test
    void projectsToMultipleDevicesOnSamePage() {
        CapturingOrchestration orchestration = new CapturingOrchestration();
        StateMachineProjectionService service = new StateMachineProjectionService(orchestration, new SectionDataCodec());

        List<Map<String, Object>> results = service.projectScene(List.of(
                page("dashboard", List.of("device-a", "device-b"), "Status", 50)
        ));

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("sent"))));
    }

    private Map<String, Object> page(String pageId, List<String> devices, String title, int progress) {
        return Map.of(
                "pageId", pageId,
                "layout", "vertical_scroll",
                "autoScroll", false,
                "autoScrollMs", 0,
                "devices", devices,
                "sections", List.of(Map.of(
                        "sectionId", "progress",
                        "sectionType", "progress_section",
                        "fields", Map.of("title", title, "progress", progress, "progressText", progress + "%")
                ))
        );
    }

    private static class CapturingOrchestration extends SectionOrchestrationService {
        private String sceneDeviceId;
        private SectionScene scene;
        private String patchDeviceId;
        private SectionPatch patch;

        CapturingOrchestration() {
            super(null, null, null, null, null);
        }

        @Override
        public boolean sendScene(String deviceId, SectionScene scene) {
            this.sceneDeviceId = deviceId;
            this.scene = scene;
            return true;
        }

        @Override
        public boolean sendPatch(String deviceId, SectionPatch patch) {
            this.patchDeviceId = deviceId;
            this.patch = patch;
            return true;
        }
    }
}
