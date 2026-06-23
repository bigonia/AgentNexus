package com.zwbd.agentnexus.sdui.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.ui.SduiUiTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SduiUiTemplateControllerTest {

    private ObjectMapper objectMapper;
    private SduiUiTemplateService templateService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        templateService = mock(SduiUiTemplateService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SduiUiTemplateController(templateService)).build();
    }

    @Test
    void routesReturnApiResponse() throws Exception {
        when(templateService.create(anyMap())).thenReturn(Map.of("templateId", "tpl-1"));
        when(templateService.list()).thenReturn(List.of(Map.of("templateId", "tpl-1")));
        when(templateService.get("tpl-1")).thenReturn(Map.of("templateId", "tpl-1"));
        when(templateService.update(eq("tpl-1"), anyMap())).thenReturn(Map.of("templateId", "tpl-1", "templateKey", "record_result_view"));
        when(templateService.delete("tpl-1")).thenReturn(Map.of("deleted", true));
        when(templateService.preview(eq("tpl-1"), anyMap())).thenReturn(Map.of("templateId", "tpl-1", "sent", false));

        String body = objectMapper.writeValueAsString(Map.of("templateKey", "record_result_view"));
        mockMvc.perform(post("/api/v1/sdui/ui-templates").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.templateId").value("tpl-1"));
        mockMvc.perform(get("/api/v1/sdui/ui-templates"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].templateId").value("tpl-1"));
        mockMvc.perform(get("/api/v1/sdui/ui-templates/tpl-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.templateId").value("tpl-1"));
        mockMvc.perform(put("/api/v1/sdui/ui-templates/tpl-1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.templateKey").value("record_result_view"));
        mockMvc.perform(post("/api/v1/sdui/ui-templates/tpl-1/preview").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sent").value(false));
        mockMvc.perform(delete("/api/v1/sdui/ui-templates/tpl-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
    }
}
