package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.artifact.SduiArtifactEntity;
import com.zwbd.agentnexus.sdui.artifact.SduiArtifactService;
import com.zwbd.agentnexus.sdui.service.audio.TtsProvider;
import com.zwbd.agentnexus.sdui.ui.WorkflowUiContextService;
import com.zwbd.agentnexus.sdui.v2.V2ProtocolProperties;
import com.zwbd.agentnexus.sdui.v2.audio.AudioCommandService;
import com.zwbd.agentnexus.sdui.v2.audio.WavPcm;
import com.zwbd.agentnexus.sdui.v2.display.DisplayCommandService;
import com.zwbd.agentnexus.sdui.v2.display.SectionViewResolver;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnectionRegistry;
import com.zwbd.agentnexus.sdui.v2.transport.PlatformRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 执行"留在平台"的工作流步骤。
 *
 * <h2>它在闭环里的位置</h2>
 * <p>新模型的闭环是（01_INTERACTION_MODEL.md §1、§4）：</p>
 * <pre>
 * 终端本地执行静态前缀 → platform.interaction.report(token)
 *   → 平台恢复上下文、算完需要平台算的部分
 *   → 把结果作为<b>新的独立命令</b>下发（§4 明确"不是原响应序列的继续"）
 * </pre>
 * <p>本类就是第三步。它只处理 {@code platformSteps}，也就是组装阶段判定为无法下沉的节点。</p>
 *
 * <h2>两类节点，两种出口</h2>
 * <ol>
 *   <li><b>平台可作为请求下发</b>：UI 走 {@code display.section}，音频走 {@code audio.*} 下行流。
 *       这是绝大多数"需要平台产出内容"的节点，例如 TTS、artifact 播放、模板渲染。</li>
 *   <li><b>平台不可作为请求下发</b>：能力 Schema 里 {@code usableIn} 只含 {@code binding} 的动作
 *       （RGB 灯效、录音）。这类动作平台无法凭请求发起，只能由终端在本地响应序列中执行。
 *       本类如实返回 {@code terminal_action_required}，<b>不</b>退化成旧协议的遥控路径。</li>
 * </ol>
 * <p>第 2 类不是缺陷而是模型的真实结果：设计把设备动作的所有权交给了终端的本地响应序列。要覆盖它
 * 需要"平台为一个动态节点生成专用绑定并签发 token"的能力，而文档没有定义这种绑定的形态
 * （登记为缺口 G25）。在定义清楚之前返回"需要终端动作"，比自造一套绑定形态更安全。</p>
 *
 * <h2>为什么不再依赖旧协议服务</h2>
 * <p>取代的是旧的 {@code CapabilityNodeExecutorService}。后者通过 {@code CommandService}（cmd/control
 * 并等 ACK）、{@code AudioService}（Base64 内联音频）、{@code SectionOrchestrationService}（scene/patch）
 * 驱动设备——三者在 v2 中都已不存在对应概念。旧实现里为等待 cmd/control ACK 而写的重试与状态轮询
 * 也没有必要：v2 的请求"只接收整条序列的一次最终结果"（01§2）。</p>
 */
@Slf4j
@Service
public class WorkflowPlatformStepExecutor {

    /** 步骤结果的状态取值。 */
    public static final String STATUS_DELIVERED = "delivered";
    public static final String STATUS_TERMINAL_ACTION_REQUIRED = "terminal_action_required";
    public static final String STATUS_UNSUPPORTED = "unsupported";
    public static final String STATUS_FAILED = "failed";

    /**
     * 一个平台步骤的执行结果。
     *
     * @param ok     是否已完成平台侧职责（{@code terminal_action_required} 视为已尽责但未交付）
     * @param status 见本类的 {@code STATUS_*} 常量
     * @param detail 供运行记录留痕的结构化细节
     */
    public record StepOutcome(boolean ok, String status, Map<String, Object> detail) {

        static StepOutcome delivered(Map<String, Object> detail) {
            return new StepOutcome(true, STATUS_DELIVERED, detail);
        }

        static StepOutcome terminalActionRequired(String reason) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", reason);
            detail.put("note", "该动作只能由终端本地响应序列执行，平台无法作为请求下发（缺口 G25）");
            return new StepOutcome(true, STATUS_TERMINAL_ACTION_REQUIRED, detail);
        }

        static StepOutcome unsupported(String nodeType) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", "未登记的输出节点类型: " + nodeType);
            return new StepOutcome(false, STATUS_UNSUPPORTED, detail);
        }

        static StepOutcome failed(String reason, Map<String, Object> extra) {
            Map<String, Object> detail = new LinkedHashMap<>(extra == null ? Map.of() : extra);
            detail.put("reason", reason);
            return new StepOutcome(false, STATUS_FAILED, detail);
        }
    }

    private final DisplayCommandService display;
    private final AudioCommandService audio;
    private final SectionViewResolver sectionViews;
    private final WorkflowUiContextService uiContext;
    private final SduiArtifactService artifacts;
    private final DeviceConnectionRegistry connections;
    private final V2ProtocolProperties properties;
    private final ObjectProvider<TtsProvider> ttsProvider;

    public WorkflowPlatformStepExecutor(DisplayCommandService display,
                                        AudioCommandService audio,
                                        SectionViewResolver sectionViews,
                                        WorkflowUiContextService uiContext,
                                        SduiArtifactService artifacts,
                                        DeviceConnectionRegistry connections,
                                        V2ProtocolProperties properties,
                                        ObjectProvider<TtsProvider> ttsProvider) {
        this.display = display;
        this.audio = audio;
        this.sectionViews = sectionViews;
        this.uiContext = uiContext;
        this.artifacts = artifacts;
        this.connections = connections;
        this.properties = properties;
        this.ttsProvider = ttsProvider;
    }

    /**
     * 执行一个平台步骤。
     *
     * @param deviceId  目标设备
     * @param nodeType  工作流输出节点类型
     * @param params    节点参数
     * @param execution 执行上下文（{@code workflowId} / {@code deploymentId} / {@code slotId} / {@code nodeId}）
     */
    public StepOutcome execute(String deviceId, String nodeType, Map<String, Object> params,
                               Map<String, Object> execution) {
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        Map<String, Object> context = execution == null ? Map.of() : execution;

        if (!connections.isOnline(deviceId)) {
            return StepOutcome.failed("设备不在线，平台步骤无法下发", Map.of("deviceId", deviceId));
        }

        return switch (nodeType) {
            case "display.section" -> executeDisplaySection(deviceId, safeParams);
            case "ui.update" -> executeUiUpdate(deviceId, safeParams, context);
            case "audio.play" -> executeAudioPlay(deviceId, safeParams);
            case "rgb.effect" -> StepOutcome.terminalActionRequired(
                    "RGB 灯效在能力 Schema 中只声明为可用于本地绑定，平台无法作为请求下发");
            case "audio.record" -> StepOutcome.terminalActionRequired(
                    "录音动作在能力 Schema 中只声明为可用于本地绑定；按 01§5 录音应由 button.down/up 直接绑定");
            default -> StepOutcome.unsupported(nodeType);
        };
    }

    // ── UI ──────────────────────────────────────────────────────────────────

    private StepOutcome executeDisplaySection(String deviceId, Map<String, Object> params) {
        if (params.get("scene") instanceof Map<?, ?> rawScene) {
            Map<String, Object> scene = stringMap(rawScene);
            return sectionViews.primaryOfScene(scene)
                    .map(section -> deliverSection(deviceId, section, "scene"))
                    .orElseGet(() -> StepOutcome.failed(
                            "scene 里没有任何 Section，无主视图可下发", Map.of("scene", scene)));
        }
        if (params.get("patch") instanceof Map<?, ?> rawPatch) {
            Map<String, Object> patch = stringMap(rawPatch);
            return sectionViews.applyPatchJson(deviceId, patch)
                    .map(section -> deliverSection(deviceId, section, "patch"))
                    .orElseGet(() -> StepOutcome.failed(
                            "平台侧没有当前主视图快照，无法把 Patch 合成为完整 Section；"
                                    + "v2 无 Section 级增量更新，应改为下发完整 Section",
                            Map.of("patch", patch)));
        }
        String sectionId = string(params.get("sectionId"));
        if (!sectionId.isBlank()) {
            // 只有 sectionId 的节点本应被下沉判定放行；走到这里说明能力 Schema 门禁拦下了它。
            return StepOutcome.terminalActionRequired(
                    "display.section 仅声明了 sectionId，说明该动作未在能力 Schema 中声明可用于本地绑定");
        }
        return StepOutcome.failed("display.section 需要 scene 或 patch 参数", Map.of("params", params));
    }

    private StepOutcome executeUiUpdate(String deviceId, Map<String, Object> params,
                                        Map<String, Object> context) {
        String workflowId = string(context.get("workflowId"));
        String deploymentId = string(context.get("deploymentId"));
        if (workflowId.isBlank() || deploymentId.isBlank()) {
            return StepOutcome.failed("ui.update 需要工作流执行上下文", Map.of("params", params));
        }
        try {
            Map<String, Object> enriched = new LinkedHashMap<>(params);
            enriched.putIfAbsent("slotId", context.get("slotId"));
            Map<String, Object> result = uiContext.updateVariable(workflowId, deploymentId, deviceId, enriched);
            boolean sent = Boolean.TRUE.equals(result.get("sent"));
            if (!sent) {
                return StepOutcome.failed("ui.update 主视图下发失败",
                        Map.of("result", result, "status", String.valueOf(result.get("status"))));
            }
            return StepOutcome.delivered(new LinkedHashMap<>(result));
        } catch (RuntimeException e) {
            return StepOutcome.failed("ui.update 执行失败: " + e.getMessage(), Map.of("params", params));
        }
    }

    private StepOutcome deliverSection(String deviceId, Map<String, Object> section, String source) {
        PlatformRequestService.Outcome outcome = await(display.sendSection(deviceId, section));
        if (!outcome.ok()) {
            return StepOutcome.failed("display.section 下发失败: " + outcome.error(),
                    Map.of("error", String.valueOf(outcome.error())));
        }
        // v2 没有 Patch，平台侧必须记住当前主视图才能合成后续的增量
        sectionViews.remember(deviceId, section);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", source);
        detail.put("sectionId", section.get("sectionId"));
        detail.put("sectionType", section.get("sectionType"));
        return StepOutcome.delivered(detail);
    }

    // ── 音频 ────────────────────────────────────────────────────────────────

    private StepOutcome executeAudioPlay(String deviceId, Map<String, Object> params) {
        String artifactRef = firstNonBlank(string(params.get("artifact_id")), string(params.get("audio_file")));
        if (!artifactRef.isBlank()) {
            return playArtifact(deviceId, artifactRef);
        }
        String text = string(params.get("text"));
        if (!text.isBlank()) {
            return playText(deviceId, text);
        }
        String preset = string(params.get("preset"));
        if (!preset.isBlank()) {
            // preset 本应在组装阶段下沉为 prompt.play；能走到这里说明终端没声明该动作。
            return StepOutcome.terminalActionRequired(
                    "prompt.play 未在能力 Schema 中声明可用于本地绑定，平台侧也无可下发的音频内容");
        }
        return StepOutcome.failed("audio.play 既没有可播放的音频来源，也没有 preset", Map.of("params", params));
    }

    private StepOutcome playArtifact(String deviceId, String artifactRef) {
        SduiArtifactService.ResolvedArtifact resolved = artifacts.resolve(deviceId, artifactRef);
        if (resolved == null || resolved.artifact() == null) {
            return StepOutcome.failed("audio artifact 不存在: " + artifactRef, Map.of("artifactId", artifactRef));
        }
        SduiArtifactEntity artifact = resolved.artifact();
        if (artifact.getBlob() == null || artifact.getBlob().length == 0) {
            return StepOutcome.failed("audio artifact 没有音频数据: " + artifact.getArtifactId(),
                    Map.of("artifactId", artifact.getArtifactId()));
        }
        WavPcm.Payload payload = WavPcm.extract(artifact.getBlob());
        if (payload.isEmpty()) {
            return StepOutcome.failed("无法从 artifact 中取出 PCM 载荷: " + artifact.getArtifactId(),
                    Map.of("artifactId", artifact.getArtifactId()));
        }
        return deliverPcm(deviceId, payload, Map.of("artifactId", artifact.getArtifactId()));
    }

    private StepOutcome playText(String deviceId, String text) {
        TtsProvider provider = ttsProvider.getIfAvailable();
        if (provider == null) {
            return StepOutcome.failed("平台未配置 TTS 引擎，无法合成文本音频", Map.of("text", text));
        }
        byte[] pcm = provider.synthesize(text);
        if (pcm == null || pcm.length == 0) {
            return StepOutcome.failed("TTS 未产出音频", Map.of("text", text));
        }
        // TtsProvider 契约：16-bit signed LE mono。采样率由实现决定，这里如实记录以便与终端声明比对。
        return deliverPcm(deviceId, new WavPcm.Payload(pcm, 0, 1, 16, false), Map.of("text", text));
    }

    /**
     * 用 v2 下行音频流播放一段 PCM。
     *
     * <p>04§7 的顺序是 {@code audio.start → 二进制帧 → audio.stop}。分片大小取协议单帧上限，
     * 不做应用层编号与重传——§7 明确首期不使用分片序号和确认位置。</p>
     */
    private StepOutcome deliverPcm(String deviceId, WavPcm.Payload payload, Map<String, Object> extra) {
        PlatformRequestService.Outcome started = await(audio.startDownlink(deviceId));
        if (!started.ok()) {
            return StepOutcome.failed("audio.start 被拒绝: " + started.error(),
                    merge(extra, Map.of("error", String.valueOf(started.error()))));
        }

        byte[] pcm = payload.pcm();
        int chunkBytes = Math.max(1, properties.getMaxBinaryFrameBytes());
        int offset = 0;
        int chunks = 0;
        while (offset < pcm.length) {
            int length = Math.min(chunkBytes, pcm.length - offset);
            byte[] chunk = Arrays.copyOfRange(pcm, offset, offset + length);
            audio.sendDownlinkAudio(deviceId, chunk);
            offset += length;
            chunks++;
        }

        // 分片是"尽力发送"，无活动流时 sendDownlinkAudio 会丢数据而不抛错。这里不逐帧校验，
        // 由 audio.stop 的最终结果兜底：终端在收到的数据不足时自行判定结束条件（01§5 播放等待时限）。
        PlatformRequestService.Outcome stopped = await(audio.stopDownlink(deviceId));

        Map<String, Object> detail = merge(extra, Map.of(
                "bytes", pcm.length,
                "chunks", chunks,
                "declaredSampleRate", payload.sampleRate(),
                "channels", payload.channels(),
                "bitsPerSample", payload.bitsPerSample(),
                "container", payload.wav() ? "wav" : "raw_pcm"
        ));
        if (!stopped.ok()) {
            audio.abortDownlink(deviceId, "stop_failed");
            return StepOutcome.failed("audio.stop 失败: " + stopped.error(), detail);
        }
        return StepOutcome.delivered(detail);
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    /**
     * 等待异步请求结果。
     *
     * <p>超出请求超时上限时按失败处理，不再等待——否则一个卡住的设备会拖死整条工作流运行。</p>
     */
    private PlatformRequestService.Outcome await(CompletableFuture<PlatformRequestService.Outcome> future) {
        try {
            return future.get(properties.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return PlatformRequestService.Outcome.failure("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PlatformRequestService.Outcome.failure("interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return PlatformRequestService.Outcome.failure(String.valueOf(cause.getMessage()));
        }
    }

    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> more) {
        Map<String, Object> merged = new LinkedHashMap<>(base == null ? Map.of() : base);
        merged.putAll(more);
        return merged;
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return map;
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return "";
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
