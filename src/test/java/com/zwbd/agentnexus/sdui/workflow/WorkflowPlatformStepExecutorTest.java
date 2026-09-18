package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.artifact.SduiArtifactEntity;
import com.zwbd.agentnexus.sdui.artifact.SduiArtifactService;
import com.zwbd.agentnexus.sdui.service.audio.TtsProvider;
import com.zwbd.agentnexus.sdui.ui.WorkflowUiContextService;
import com.zwbd.agentnexus.sdui.v2.V2ProtocolProperties;
import com.zwbd.agentnexus.sdui.v2.audio.AudioCommandService;
import com.zwbd.agentnexus.sdui.v2.display.DisplayCommandService;
import com.zwbd.agentnexus.sdui.v2.display.SectionViewResolver;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnectionRegistry;
import com.zwbd.agentnexus.sdui.v2.transport.PlatformRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台步骤执行器的分派与出口选择。
 *
 * <p>这些用例固定 P5b 的核心取舍：能作为 v2 请求下发的就走 v2（UI、音频下行），只能由终端本地
 * 响应序列执行的动作如实返回 {@code terminal_action_required}，绝不退回旧协议的遥控路径。</p>
 */
class WorkflowPlatformStepExecutorTest {

    private DisplayCommandService display;
    private AudioCommandService audio;
    private SectionViewResolver sectionViews;
    private WorkflowUiContextService uiContext;
    private SduiArtifactService artifacts;
    private DeviceConnectionRegistry connections;
    private ObjectProvider<TtsProvider> ttsProvider;

    private WorkflowPlatformStepExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        display = mock(DisplayCommandService.class);
        audio = mock(AudioCommandService.class);
        sectionViews = mock(SectionViewResolver.class);
        uiContext = mock(WorkflowUiContextService.class);
        artifacts = mock(SduiArtifactService.class);
        connections = mock(DeviceConnectionRegistry.class);
        ttsProvider = mock(ObjectProvider.class);

        when(connections.isOnline("dev-1")).thenReturn(true);
        when(display.sendSection(anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(PlatformRequestService.Outcome.success()));
        when(audio.startDownlink(anyString()))
                .thenReturn(CompletableFuture.completedFuture(PlatformRequestService.Outcome.success()));
        when(audio.stopDownlink(anyString()))
                .thenReturn(CompletableFuture.completedFuture(PlatformRequestService.Outcome.success()));
        when(audio.sendDownlinkAudio(anyString(), any())).thenReturn(true);

        executor = new WorkflowPlatformStepExecutor(display, audio, sectionViews, uiContext,
                artifacts, connections, new V2ProtocolProperties(), ttsProvider);
    }

    @Test
    @DisplayName("设备离线时平台步骤直接失败，不做任何下发")
    void offlineDeviceFailsFast() {
        when(connections.isOnline("dev-1")).thenReturn(false);

        WorkflowPlatformStepExecutor.StepOutcome outcome =
                executor.execute("dev-1", "display.section", Map.of("sectionId", "s"), Map.of());

        assertFalse(outcome.ok());
        assertEquals(WorkflowPlatformStepExecutor.STATUS_FAILED, outcome.status());
        verify(display, never()).sendSection(anyString(), any());
    }

    @Test
    @DisplayName("RGB 与录音是终端本地动作，不退回旧协议遥控")
    void terminalOnlyActionsAreReportedNotFaked() {
        WorkflowPlatformStepExecutor.StepOutcome rgb =
                executor.execute("dev-1", "rgb.effect", Map.of("mode", "pulse"), Map.of());
        WorkflowPlatformStepExecutor.StepOutcome record =
                executor.execute("dev-1", "audio.record", Map.of("control", "toggle"), Map.of());

        assertEquals(WorkflowPlatformStepExecutor.STATUS_TERMINAL_ACTION_REQUIRED, rgb.status());
        assertTrue(rgb.ok(), "终端负责本地执行，平台侧已尽责");
        assertEquals(WorkflowPlatformStepExecutor.STATUS_TERMINAL_ACTION_REQUIRED, record.status());
    }

    @Test
    @DisplayName("未登记的节点类型视为不支持")
    void unknownNodeTypeIsUnsupported() {
        WorkflowPlatformStepExecutor.StepOutcome outcome =
                executor.execute("dev-1", "video.play", Map.of(), Map.of());

        assertEquals(WorkflowPlatformStepExecutor.STATUS_UNSUPPORTED, outcome.status());
        assertFalse(outcome.ok());
    }

    @Test
    @DisplayName("display.section 把 scene 收敛为单个 Section 后下发并记住快照")
    void displaySectionResolvesSceneThenPublishes() {
        Map<String, Object> section = Map.of("sectionId", "status", "sectionType", "status_section",
                "fields", Map.of("title", "hello"));
        when(sectionViews.primaryOfScene(any())).thenReturn(java.util.Optional.of(section));

        WorkflowPlatformStepExecutor.StepOutcome outcome = executor.execute("dev-1", "display.section",
                Map.of("scene", Map.of("pageId", "main")), Map.of());

        assertEquals(WorkflowPlatformStepExecutor.STATUS_DELIVERED, outcome.status());
        assertEquals("status", outcome.detail().get("sectionId"));
        verify(display).sendSection("dev-1", section);
        verify(sectionViews).remember("dev-1", section);
    }

    @Test
    @DisplayName("scene 里没有 Section 时失败，而不是下发空影")
    void displaySectionWithoutSectionsFails() {
        when(sectionViews.primaryOfScene(any())).thenReturn(java.util.Optional.empty());

        WorkflowPlatformStepExecutor.StepOutcome outcome = executor.execute("dev-1", "display.section",
                Map.of("scene", Map.of("pageId", "main")), Map.of());

        assertFalse(outcome.ok());
        verify(display, never()).sendSection(anyString(), any());
    }

    @Test
    @DisplayName("没有主视图基底时 Patch 无法合成，明确失败并说明原因")
    void patchWithoutSnapshotFails() {
        when(sectionViews.applyPatchJson(eq("dev-1"), any())).thenReturn(java.util.Optional.empty());

        WorkflowPlatformStepExecutor.StepOutcome outcome = executor.execute("dev-1", "display.section",
                Map.of("patch", Map.of("patches", List.of())), Map.of());

        assertFalse(outcome.ok());
        assertTrue(String.valueOf(outcome.detail().get("reason")).contains("Patch"));
    }

    @Test
    @DisplayName("audio.play 用 artifact 走 v2 下行音频流")
    void audioPlayStreamsArtifact() {
        byte[] pcm = {1, 2, 3, 4, 5, 6};
        SduiArtifactEntity artifact = new SduiArtifactEntity();
        artifact.setArtifactId("art-1");
        artifact.setBlob(wav(pcm, 16000));
        when(artifacts.resolve("dev-1", "art-1"))
                .thenReturn(new SduiArtifactService.ResolvedArtifact("art-1", artifact));

        WorkflowPlatformStepExecutor.StepOutcome outcome = executor.execute("dev-1", "audio.play",
                Map.of("artifact_id", "art-1"), Map.of());

        assertEquals(WorkflowPlatformStepExecutor.STATUS_DELIVERED, outcome.status());
        verify(audio).startDownlink("dev-1");
        ArgumentCaptor<byte[]> chunk = ArgumentCaptor.forClass(byte[].class);
        verify(audio).sendDownlinkAudio(eq("dev-1"), chunk.capture());
        assertEquals(pcm.length, chunk.getValue().length);
        verify(audio).stopDownlink("dev-1");
        assertEquals(16000, outcome.detail().get("declaredSampleRate"));
        assertEquals(6, outcome.detail().get("bytes"));
    }

    @Test
    @DisplayName("artifact 不存在时失败，不产生空洞的播放流")
    void audioPlayMissingArtifactFails() {
        when(artifacts.resolve("dev-1", "missing")).thenReturn(null);

        WorkflowPlatformStepExecutor.StepOutcome outcome = executor.execute("dev-1", "audio.play",
                Map.of("artifact_id", "missing"), Map.of());

        assertFalse(outcome.ok());
        verify(audio, never()).startDownlink(anyString());
    }

    @Test
    @DisplayName("纯静态 preset 本应在组装阶段下沉；走到这里说明终端未声明该动作")
    void staticPresetReachingPlatformMeansTerminalLacksBinding() {
        WorkflowPlatformStepExecutor.StepOutcome outcome = executor.execute("dev-1", "audio.play",
                Map.of("preset", "start"), Map.of());

        assertEquals(WorkflowPlatformStepExecutor.STATUS_TERMINAL_ACTION_REQUIRED, outcome.status());
        verify(audio, never()).startDownlink(anyString());
    }

    @Test
    @DisplayName("有文本但平台没有 TTS 引擎时失败")
    void textWithoutTtsFails() {
        when(ttsProvider.getIfAvailable()).thenReturn(null);

        WorkflowPlatformStepExecutor.StepOutcome outcome = executor.execute("dev-1", "audio.play",
                Map.of("text", "你好"), Map.of());

        assertFalse(outcome.ok());
        assertTrue(String.valueOf(outcome.detail().get("reason")).contains("TTS"));
    }

    @Test
    @DisplayName("文本经 TTS 合成后按单帧上限分片下发")
    void textIsSynthesizedThenChunked() {
        byte[] pcm = new byte[20];
        TtsProvider provider = mock(TtsProvider.class);
        when(provider.synthesize("你好")).thenReturn(pcm);
        when(ttsProvider.getIfAvailable()).thenReturn(provider);

        V2ProtocolProperties tight = new V2ProtocolProperties();
        tight.setMaxBinaryFrameBytes(8);
        WorkflowPlatformStepExecutor chunking = new WorkflowPlatformStepExecutor(display, audio,
                sectionViews, uiContext, artifacts, connections, tight, ttsProvider);

        WorkflowPlatformStepExecutor.StepOutcome outcome =
                chunking.execute("dev-1", "audio.play", Map.of("text", "你好"), Map.of());

        assertEquals(WorkflowPlatformStepExecutor.STATUS_DELIVERED, outcome.status());
        assertEquals(3, outcome.detail().get("chunks"));
        verify(audio, org.mockito.Mockito.times(3)).sendDownlinkAudio(eq("dev-1"), any());
    }

    @Test
    @DisplayName("音频流开始被拒时不再发送数据")
    void rejectedStartSkipsStreaming() {
        SduiArtifactEntity artifact = new SduiArtifactEntity();
        artifact.setArtifactId("art-1");
        artifact.setBlob(wav(new byte[]{1, 2, 3, 4}, 16000));
        when(artifacts.resolve("dev-1", "art-1"))
                .thenReturn(new SduiArtifactService.ResolvedArtifact("art-1", artifact));
        when(audio.startDownlink("dev-1")).thenReturn(CompletableFuture.completedFuture(
                PlatformRequestService.Outcome.failure("audio_busy")));

        WorkflowPlatformStepExecutor.StepOutcome outcome = executor.execute("dev-1", "audio.play",
                Map.of("artifact_id", "art-1"), Map.of());

        assertFalse(outcome.ok());
        verify(audio, never()).sendDownlinkAudio(anyString(), any());
    }

    @Test
    @DisplayName("ui.update 缺工作流上下文时失败，不猜部署")
    void uiUpdateRequiresWorkflowContext() {
        WorkflowPlatformStepExecutor.StepOutcome outcome = executor.execute("dev-1", "ui.update",
                Map.of("variableKey", "count", "value", 1), Map.of());

        assertFalse(outcome.ok());
        verify(uiContext, never()).updateVariable(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("ui.update 复用 UI 上下文的变量应用与主视图下发")
    void uiUpdateDelegatesToUiContext() {
        when(uiContext.updateVariable(eq("wf-1"), eq("dep-1"), eq("dev-1"), any()))
                .thenReturn(new LinkedHashMap<>(Map.of("sent", true, "sectionId", "status")));

        WorkflowPlatformStepExecutor.StepOutcome outcome = executor.execute("dev-1", "ui.update",
                Map.of("variableKey", "count", "value", 1),
                Map.of("workflowId", "wf-1", "deploymentId", "dep-1", "slotId", "target"));

        assertEquals(WorkflowPlatformStepExecutor.STATUS_DELIVERED, outcome.status());
        assertEquals("status", outcome.detail().get("sectionId"));
    }

    @Test
    @DisplayName("ui.update 下发失败时如实反映为步骤失败")
    void uiUpdateSendFailurePropagates() {
        when(uiContext.updateVariable(anyString(), anyString(), anyString(), any()))
                .thenReturn(new LinkedHashMap<>(Map.of("sent", false, "status", "send_failed")));

        WorkflowPlatformStepExecutor.StepOutcome outcome = executor.execute("dev-1", "ui.update",
                Map.of("variableKey", "count"),
                Map.of("workflowId", "wf-1", "deploymentId", "dep-1"));

        assertFalse(outcome.ok());
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private static byte[] wav(byte[] pcm, int sampleRate) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(intLe(0));
        out.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes("fmt ".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(intLe(16));
        ByteBuffer fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        fmt.putShort((short) 1);
        fmt.putShort((short) 1);
        fmt.putInt(sampleRate);
        fmt.putInt(sampleRate * 2);
        fmt.putShort((short) 2);
        fmt.putShort((short) 16);
        out.writeBytes(fmt.array());
        out.writeBytes("data".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(intLe(pcm.length));
        out.writeBytes(pcm);
        return out.toByteArray();
    }

    private static byte[] intLe(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }
}
