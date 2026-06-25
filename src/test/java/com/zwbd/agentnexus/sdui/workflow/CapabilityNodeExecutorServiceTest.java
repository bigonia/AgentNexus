package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.artifact.SduiArtifactEntity;
import com.zwbd.agentnexus.sdui.artifact.SduiArtifactService;
import com.zwbd.agentnexus.sdui.dto.SduiControlDispatchResult;
import com.zwbd.agentnexus.sdui.section.SectionDataCodec;
import com.zwbd.agentnexus.sdui.section.SectionOrchestrationService;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import com.zwbd.agentnexus.sdui.section.SectionPatch;
import com.zwbd.agentnexus.sdui.section.SectionScene;
import com.zwbd.agentnexus.sdui.service.AudioService;
import com.zwbd.agentnexus.sdui.service.CommandService;
import com.zwbd.agentnexus.sdui.service.audio.AudioRecordSessionManager;
import com.zwbd.agentnexus.sdui.ui.WorkflowUiContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CapabilityNodeExecutorServiceTest {

    private DeviceSessionManager sessionManager;
    private SectionOrchestrationService sectionService;
    private SduiArtifactService artifactService;
    private CommandService commandService;
    private AudioRecordSessionManager audioRecordSessionManager;
    private SectionTypeCatalog mockCatalog;
    private CapabilityNodeExecutorService executor;

    @BeforeEach
    void setUp() {
        sessionManager = mock(DeviceSessionManager.class);
        sectionService = mock(SectionOrchestrationService.class);
        artifactService = mock(SduiArtifactService.class);
        commandService = mock(CommandService.class);
        audioRecordSessionManager = mock(AudioRecordSessionManager.class);
        mockCatalog = mock(SectionTypeCatalog.class);
        when(mockCatalog.get(anyString())).thenReturn(Optional.empty());
        when(mockCatalog.isValidType(anyString())).thenReturn(true);
        executor = new CapabilityNodeExecutorService(
                sessionManager,
                commandService,
                mock(AudioService.class),
                audioRecordSessionManager,
                artifactService,
                sectionService,
                new SectionDataCodec(mockCatalog),
                mock(WorkflowUiContextService.class),
                mockCatalog
        );
        when(sessionManager.isDeviceOnline("dev-1")).thenReturn(true);
    }

    @Test
    void audioRecordStopWaitsForNewArtifact() {
        SduiArtifactEntity oldArtifact = artifact("old-artifact");
        SduiArtifactEntity newArtifact = artifact("new-artifact");
        when(commandService.dispatchCommand(eq("dev-1"), eq("audio.record.stop"), anyMap()))
                .thenReturn(new SduiControlDispatchResult("cmd-1", "audio_record_stop", null, true, "SENT"));
        when(commandService.waitForControlAck("dev-1", "cmd-1", 2000L)).thenReturn("ACKED");
        when(audioRecordSessionManager.isRecording("dev-1")).thenReturn(false);
        when(artifactService.findLatest("dev-1", SduiArtifactService.AUDIO_RECORDING))
                .thenReturn(Optional.of(oldArtifact), Optional.of(newArtifact));
        when(artifactService.toMap(newArtifact)).thenReturn(Map.of(
                "artifactId", "new-artifact",
                "artifactRef", "artifact:new-artifact"
        ));

        Map<String, Object> result = executor.execute("dev-1", "audio.record", Map.of("control", "stop"));

        assertEquals("sent", result.get("status"));
        assertTrue(result.get("artifact") instanceof Map<?, ?>);
        assertEquals("artifact:new-artifact", ((Map<?, ?>) result.get("artifact")).get("artifactRef"));
    }

    @Test
    void audioRecordStopBlocksUntilRecordingSessionEndsThenWaitsForArtifact() {
        SduiArtifactEntity oldArtifact = artifact("old-artifact");
        SduiArtifactEntity newArtifact = artifact("new-artifact");
        when(commandService.dispatchCommand(eq("dev-1"), eq("audio.record.stop"), anyMap()))
                .thenReturn(new SduiControlDispatchResult("cmd-1", "audio_record_stop", null, true, "SENT"));
        when(commandService.waitForControlAck("dev-1", "cmd-1", 2000L)).thenReturn("ACKED");
        when(audioRecordSessionManager.isRecording("dev-1"))
                .thenReturn(true, true, false, false);
        when(artifactService.findLatest("dev-1", SduiArtifactService.AUDIO_RECORDING))
                .thenReturn(Optional.of(oldArtifact), Optional.of(oldArtifact), Optional.of(newArtifact));
        when(artifactService.toMap(newArtifact)).thenReturn(Map.of(
                "artifactId", "new-artifact",
                "artifactRef", "artifact:new-artifact"
        ));

        Map<String, Object> result = executor.execute("dev-1", "audio.record", Map.of("control", "stop"));

        assertEquals("sent", result.get("status"));
        assertEquals("artifact:new-artifact", ((Map<?, ?>) result.get("artifact")).get("artifactRef"));
        verify(audioRecordSessionManager, atLeast(3)).isRecording("dev-1");
    }

    @Test
    void audioRecordStopForceFinalizesBufferedPcmWhenAckIsMissing() {
        SduiArtifactEntity forcedArtifact = artifact("forced-artifact");
        when(commandService.dispatchCommand(eq("dev-1"), eq("audio.record.stop"), anyMap()))
                .thenReturn(new SduiControlDispatchResult("cmd-1", "audio_record_stop", null, true, "SENT"))
                .thenReturn(new SduiControlDispatchResult("cmd-2", "audio_record_stop", null, true, "SENT"))
                .thenReturn(new SduiControlDispatchResult("cmd-3", "audio_record_stop", null, true, "SENT"));
        when(commandService.waitForControlAck(eq("dev-1"), anyString(), eq(2000L))).thenReturn("ACK_TIMEOUT");
        when(artifactService.findLatest("dev-1", SduiArtifactService.AUDIO_RECORDING))
                .thenReturn(Optional.empty());
        when(audioRecordSessionManager.stopSession("dev-1")).thenReturn(new byte[]{1, 0, 2, 0});
        when(artifactService.save(eq("dev-1"), eq(SduiArtifactService.AUDIO_RECORDING), eq("audio/wav"), anyMap(), any()))
                .thenReturn(forcedArtifact);
        when(artifactService.toMap(forcedArtifact)).thenReturn(Map.of(
                "artifactId", "forced-artifact",
                "artifactRef", "artifact:forced-artifact",
                "metadata", Map.of("forcedFinalize", true)
        ));

        Map<String, Object> result = executor.execute("dev-1", "audio.record", Map.of("control", "stop"));

        assertEquals("forced_finalized", result.get("status"));
        assertEquals(true, result.get("forcedFinalize"));
        assertEquals("artifact:forced-artifact", ((Map<?, ?>) result.get("artifact")).get("artifactRef"));
        verify(commandService, times(3)).dispatchCommand(eq("dev-1"), eq("audio.record.stop"), anyMap());
    }

    @Test
    void uiUpdateCanSendScene() {
        when(sectionService.sendScene(eq("dev-1"), any())).thenReturn(true);

        Map<String, Object> result = executor.execute("dev-1", "ui.update", Map.of(
                "scene", Map.of(
                        "pageId", "main",
                        "sections", List.of(Map.of(
                                "sectionId", "text1",
                                "sectionType", "text_section",
                                "fields", Map.of("title", "T", "body", "B")
                        ))
                )
        ));

        assertEquals("sent", result.get("status"));
        assertEquals("scene", result.get("operation"));
        ArgumentCaptor<SectionScene> sceneCaptor = ArgumentCaptor.forClass(SectionScene.class);
        verify(sectionService).sendScene(eq("dev-1"), sceneCaptor.capture());
        assertEquals("main", sceneCaptor.getValue().pageId());
    }

    @Test
    void displaySectionCanSendPatch() {
        when(sectionService.sendPatch(eq("dev-1"), any())).thenReturn(true);

        Map<String, Object> result = executor.execute("dev-1", "display.section", Map.of(
                "patch", Map.of(
                        "pageId", "main",
                        "patches", List.of(Map.of(
                                "sectionId", "text1",
                                "op", "update",
                                "sectionType", "text_section",
                                "fields", Map.of("body", "updated")
                        ))
                )
        ));

        assertEquals("sent", result.get("status"));
        assertEquals("patch", result.get("operation"));
        ArgumentCaptor<SectionPatch> patchCaptor = ArgumentCaptor.forClass(SectionPatch.class);
        verify(sectionService).sendPatch(eq("dev-1"), patchCaptor.capture());
        assertEquals("main", patchCaptor.getValue().pageId());
    }

    private SduiArtifactEntity artifact(String artifactId) {
        SduiArtifactEntity artifact = new SduiArtifactEntity();
        artifact.setArtifactId(artifactId);
        artifact.setDeviceId("dev-1");
        artifact.setType(SduiArtifactService.AUDIO_RECORDING);
        artifact.setMimeType("audio/wav");
        artifact.setBlob(new byte[]{1, 2, 3});
        return artifact;
    }
}
