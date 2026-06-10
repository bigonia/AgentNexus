package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.capability.CapabilityContractService;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.section.SectionEditorService;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class WorkflowEditorServiceTest {

    private WorkflowEditorService workflowEditorService;

    @BeforeEach
    void setUp() {
        workflowEditorService = new WorkflowEditorService(
                new ObjectMapper(),
                mock(WorkflowDefinitionRepository.class),
                mock(CapabilityContractService.class),
                mock(SectionEditorService.class),
                mock(DeviceCapabilityProjection.class),
                new WorkflowDefinitionNormalizer()
        );
    }

    @Test
    void buildNodeCatalogAddsDefaultConfigAndGroups() {
        NodeSchema schema = new NodeSchema(
                "device.page.switch",
                "切换页面",
                "切换页面",
                "device",
                "arrow-right-circle",
                List.of(new NodeSchema.ParamDef("page", "string", true, "home", "页面")),
                List.of(),
                false,
                1000,
                true,
                "device",
                "section_scene",
                "device.ui.page",
                Map.of()
        );

        Map<String, Object> catalog = workflowEditorService.buildNodeCatalog(List.of(schema));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) catalog.get("nodes");
        assertEquals(1, nodes.size());
        assertEquals(Map.of("page", "home"), nodes.get(0).get("defaultConfig"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) catalog.get("groups");
        assertEquals("device", groups.get(0).get("group"));
    }

    @Test
    void scaffoldDefinitionBuildsNodeActionsFromGraphNodes() {
        WorkflowDefinitionEntity entity = workflowEditorService.scaffoldDefinition(Map.of(
                "id", "wf-1",
                "name", "Test Workflow",
                "graph", Map.of(
                        "triggers", List.of(Map.of("type", "manual", "id", "start")),
                        "nodes", List.of(Map.of(
                                "id", "n1",
                                "nodeType", "device.page.switch",
                                "params", Map.of("page", "home")
                        )),
                        "edges", List.of(Map.of("from", "start", "to", "n1"))
                ),
                "pages", List.of()
        ));

        assertEquals("wf-1", entity.getId());
        assertTrue(entity.getDefinitionJson().contains("\"nodeType\":\"device.page.switch\""));
        assertTrue(entity.getDefinitionJson().contains("\"id\":\"start\""));
    }

    @Test
    void scaffoldDefinitionNormalizesMissingPageAndSectionIds() {
        WorkflowDefinitionEntity entity = workflowEditorService.scaffoldDefinition(Map.of(
                "id", "wf-2",
                "name", "Normalized Workflow",
                "graph", Map.of(
                        "triggers", List.of(Map.of("type", "manual", "id", "start")),
                        "nodes", List.of(),
                        "edges", List.of()
                ),
                "pages", List.of(Map.of(
                        "layout", "vertical_scroll",
                        "sections", List.of(Map.of(
                                "type", "hero_section",
                                "bind", Map.of("value", "$data.value")
                        ))
                ))
        ));

        assertTrue(entity.getDefinitionJson().contains("\"id\":\"workflow_page_1\""));
        assertTrue(entity.getDefinitionJson().contains("\"id\":\"workflow_section_workflow_page_1_1\""));
    }
}
