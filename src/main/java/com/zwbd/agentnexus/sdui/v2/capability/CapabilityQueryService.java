package com.zwbd.agentnexus.sdui.v2.capability;

import com.zwbd.agentnexus.common.web.GlobalContext;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnectionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 能力查询的唯一出口。
 *
 * <p>管理面（能力域、板型域、调试域、工作流编辑器的节点目录）全部经本类读取设备能力，
 * 不再存在第二份能力快照。旧实现有两条并行的能力来源：设备上报的"能力名称列表"
 * （{@code CapabilitySchema.CapabilitySnapshot}）与静态能力目录（{@code CapabilityCatalog}）。
 * v2 只有一个来源——设备通过 {@code capability_hash} + 完整 Schema 声明的机器可校验能力
 * （04_PROTOCOL_MODEL.md §4）。因此本类的所有视图都由 {@link CapabilitySchemaV2} 推导，
 * 不引入任何平台侧的能力推断。</p>
 *
 * <p>在线态同样只认一个来源：v2 连接注册表。旧 {@code DeviceSessionManager} 属于将被剪除的
 * 旧协议接入层，管理面不再读它。</p>
 *
 * <p>租户边界：{@code sdui_device} 是全局表（以 {@code user_id} 列区分归属），因此按租户过滤
 * 在本类内完成，而不是依赖 Hibernate 的自动过滤。</p>
 */
@Service
@RequiredArgsConstructor
public class CapabilityQueryService {

    private final CapabilityRegistryV2 capabilities;
    private final DeviceConnectionRegistry connections;
    private final SduiDeviceRepository devices;

    // ── 设备与在线态 ────────────────────────────────────────────────────────

    /** 当前租户可见的设备。 */
    public List<SduiDevice> visibleDevices() {
        return devices.findByOwnerUserId(GlobalContext.getUserId());
    }

    /** 当前租户可见的指定设备。 */
    public Optional<SduiDevice> device(String deviceId) {
        String owner = GlobalContext.getUserId();
        return devices.findById(deviceId)
                .filter(d -> owner != null && owner.equals(d.getOwnerUserId()));
    }

    /** 在线态的唯一判据。 */
    public boolean online(String deviceId) {
        return connections.isOnline(deviceId);
    }

    /** 设备当前生效的能力 Schema；未完成同步时为空。 */
    public Optional<CapabilitySchemaV2> schemaOf(String deviceId) {
        return capabilities.schemaFor(deviceId);
    }

    /** 设备板型；未完成能力同步时无法确定。 */
    public Optional<String> boardOf(String deviceId) {
        return schemaOf(deviceId).map(CapabilitySchemaV2::board);
    }

    /**
     * 设备允许的 Section 类型（{@code surface.ui.sectionTypes}）。
     *
     * <p>UI 模板的可用性门禁读这里——设备声明它接受哪些 Section 类型，平台据此判断
     * 某个模板能否下发给它。v2 已裁决 Section 类型的能力门禁归 {@code CapabilitySchemaV2}
     * 承担（{@code v2.display.SectionViewResolver} 明确不在渲染侧二次判断），因此这是
     * 该问题的唯一来源，不再并读旧的 {@code CapabilitySnapshot}。</p>
     *
     * <p>未完成能力同步的设备返回空集——调用方据此拒绝下发，而不是回退到旧能力快照。</p>
     */
    public Set<String> sectionTypes(String deviceId) {
        CapabilitySchemaV2 schema = schemaOf(deviceId).orElse(null);
        if (schema == null || schema.surface() == null || schema.surface().ui() == null
                || schema.surface().ui().sectionTypes() == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(schema.surface().ui().sectionTypes());
    }

    // ── 能力域视图 ─────────────────────────────────────────────────────────

    /**
     * 能力摘要：回答"这台设备现在能做什么、同步到哪一步"。
     * 不返回完整 Schema——那由 {@code /schema} 单独承担，避免详情接口随 Schema 体积膨胀。
     */
    public Map<String, Object> summary(String deviceId) {
        CapabilitySchemaV2 schema = schemaOf(deviceId).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("online", online(deviceId));
        result.put("syncState", capabilities.syncStateOf(deviceId).name());
        result.put("capabilityHash", capabilities.claimedHashOf(deviceId).orElse(null));
        result.put("schemaSynced", schema != null);
        if (schema == null) {
            result.put("board", null);
            result.put("protocolVersion", null);
            result.put("schemaVersion", null);
            result.put("actionCount", 0);
            result.put("triggerCount", 0);
            result.put("surface", null);
            return result;
        }
        result.put("board", schema.board());
        result.put("protocolVersion", schema.protocolVersion());
        result.put("schemaVersion", schema.schemaVersion());
        result.put("actionCount", schema.actions() == null ? 0 : schema.actions().size());
        result.put("triggerCount", schema.triggers() == null ? 0 : schema.triggers().size());
        result.put("surface", surfaceOf(schema));
        return result;
    }

    /** 动作目录。{@code usableIn} 决定该动作能否出现在本地响应序列、能否被平台请求。 */
    public List<Map<String, Object>> actions(String deviceId) {
        CapabilitySchemaV2 schema = schemaOf(deviceId).orElse(null);
        if (schema == null || schema.actions() == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (CapabilitySchemaV2.ActionSpec action : schema.actions()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", action.name());
            entry.put("usableIn", action.usableIn() == null ? List.of() : action.usableIn());
            entry.put("usableInBinding", action.usableInBinding());
            entry.put("usableInRequest", action.usableInRequest());
            entry.put("params", paramsOf(action));
            result.add(entry);
        }
        return result;
    }

    /** 可配置触发源目录。"可配置"是能否出现在工作流绑定表里的判据。 */
    public List<Map<String, Object>> triggers(String deviceId) {
        CapabilitySchemaV2 schema = schemaOf(deviceId).orElse(null);
        if (schema == null || schema.triggers() == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (CapabilitySchemaV2.TriggerSpec trigger : schema.triggers()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", trigger.id());
            entry.put("source", trigger.source());
            entry.put("configurable", trigger.configurable());
            entry.put("maxResponses", trigger.maxResponses());
            result.add(entry);
        }
        return result;
    }

    /** 能力同步进度：定位"为什么这台设备还不能接业务"。 */
    public Map<String, Object> sync(String deviceId) {
        CapabilityRegistryV2.SyncState state = capabilities.syncStateOf(deviceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("online", online(deviceId));
        result.put("state", state.name());
        result.put("claimedHash", capabilities.claimedHashOf(deviceId).orElse(null));
        result.put("hashKnown", capabilities.claimedHashOf(deviceId)
                .map(capabilities::isKnownHash).orElse(false));
        result.put("needsSchemaUpload", capabilities.needsSchemaUpload(deviceId));
        result.put("businessAllowed", state.allowsBusiness());
        return result;
    }

    /** 全平台能力概览。 */
    public Map<String, Object> overview() {
        List<SduiDevice> devices = visibleDevices();
        long synced = devices.stream()
                .filter(d -> capabilities.syncStateOf(d.getDeviceId()) == CapabilityRegistryV2.SyncState.SYNCED)
                .count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceCount", devices.size());
        result.put("onlineCount", devices.stream().filter(d -> online(d.getDeviceId())).count());
        result.put("syncedCount", synced);
        result.put("cachedSchemaCount", capabilities.cachedSchemaCount());
        result.put("boards", boards());
        return result;
    }

    // ── 板型域视图 ─────────────────────────────────────────────────────────

    /**
     * 板型聚合。板型来自设备声明的 Schema——没有完成能力同步的设备无法归入任何板型，
     * 因此这里同时暴露未归类的设备数，避免"设备明明在但板型列表里没有它"这类静默丢失。
     */
    public List<Map<String, Object>> boards() {
        Map<String, BoardAggregate> aggregates = new LinkedHashMap<>();
        int unclassified = 0;
        for (SduiDevice device : visibleDevices()) {
            CapabilitySchemaV2 schema = schemaOf(device.getDeviceId()).orElse(null);
            if (schema == null || schema.board() == null || schema.board().isBlank()) {
                unclassified++;
                continue;
            }
            BoardAggregate aggregate = aggregates.computeIfAbsent(schema.board(),
                    board -> new BoardAggregate(board, schema));
            aggregate.add(device, online(device.getDeviceId()));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (BoardAggregate aggregate : aggregates.values()) {
            result.add(aggregate.toMap());
        }
        result.sort(Comparator.comparing(m -> String.valueOf(m.get("board"))));
        if (unclassified > 0) {
            Map<String, Object> unknown = new LinkedHashMap<>();
            unknown.put("board", null);
            unknown.put("label", "未完成能力同步");
            unknown.put("deviceCount", unclassified);
            unknown.put("onlineCount", 0);
            unknown.put("syncedCount", 0);
            unknown.put("exampleDeviceIds", List.of());
            unknown.put("actionCount", 0);
            unknown.put("triggerCount", 0);
            result.add(unknown);
        }
        return result;
    }

    /** 板型详情；板型不存在时为空。 */
    public Optional<Map<String, Object>> board(String board) {
        return boards().stream()
                .filter(item -> board != null && board.equals(item.get("board")))
                .findFirst();
    }

    /** 板型的一个代表设备：优先在线，其次任意一台。用于按板型回答设备级问题。 */
    public Optional<String> representativeDevice(String board) {
        List<String> candidates = new ArrayList<>();
        for (SduiDevice device : visibleDevices()) {
            if (board != null && board.equals(boardOf(device.getDeviceId()).orElse(null))) {
                candidates.add(device.getDeviceId());
            }
        }
        return candidates.stream().filter(this::online).findFirst()
                .or(() -> candidates.stream().findFirst());
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private Map<String, Object> surfaceOf(CapabilitySchemaV2 schema) {
        CapabilitySchemaV2.Surface surface = schema.surface();
        if (surface == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        if (surface.screen() != null) {
            map.put("screen", Map.of(
                    "width", surface.screen().width(),
                    "height", surface.screen().height(),
                    "touch", surface.screen().touch()));
        }
        if (surface.ui() != null) {
            map.put("ui", Map.of(
                    "sectionTypes", nullSafe(surface.ui().sectionTypes()),
                    "viewModes", nullSafe(surface.ui().viewModes())));
        }
        if (surface.image() != null) {
            map.put("image", Map.of(
                    "maxBytes", nullSafe(surface.image().maxBytes()),
                    "maxPaletteColors", nullSafe(surface.image().maxPaletteColors())));
        }
        if (surface.canvas() != null) {
            map.put("canvas", Map.of(
                    "resolutions", nullSafe(surface.canvas().resolutions()),
                    "colorBits", nullSafe(surface.canvas().colorBits()),
                    "maxFps", nullSafe(surface.canvas().maxFps())));
        }
        if (surface.audio() != null) {
            map.put("audio", Map.of(
                    "formats", nullSafe(surface.audio().formats()),
                    "sampleRate", nullSafe(surface.audio().sampleRate()),
                    "maxBufferBytes", nullSafe(surface.audio().maxBufferBytes())));
        }
        return map;
    }

    private List<Map<String, Object>> paramsOf(CapabilitySchemaV2.ActionSpec action) {
        if (action.params() == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (CapabilitySchemaV2.ParamSpec param : action.params()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", param.name());
            entry.put("type", param.type());
            entry.put("required", param.required());
            if (param.min() != null) {
                entry.put("min", param.min());
            }
            if (param.max() != null) {
                entry.put("max", param.max());
            }
            if (param.values() != null && !param.values().isEmpty()) {
                entry.put("values", param.values());
            }
            result.add(entry);
        }
        return result;
    }

    private static <T> Object nullSafe(T value) {
        return value == null ? List.of() : value;
    }

    /** 一个板型的设备聚合。 */
    private static final class BoardAggregate {

        private final String board;
        private final CapabilitySchemaV2 reference;
        private final Set<String> exampleDeviceIds = new LinkedHashSet<>();
        private int deviceCount;
        private int onlineCount;

        private BoardAggregate(String board, CapabilitySchemaV2 reference) {
            this.board = board;
            this.reference = reference;
        }

        private void add(SduiDevice device, boolean online) {
            deviceCount++;
            if (online) {
                onlineCount++;
            }
            if (exampleDeviceIds.size() < 5) {
                exampleDeviceIds.add(device.getDeviceId());
            }
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("board", board);
            map.put("label", board);
            map.put("deviceCount", deviceCount);
            map.put("onlineCount", onlineCount);
            // 能归入板型即意味着该设备的 Schema 已同步，故 syncedCount 恒等于 deviceCount。
            map.put("syncedCount", deviceCount);
            map.put("exampleDeviceIds", new ArrayList<>(exampleDeviceIds));
            map.put("actionCount", reference.actions() == null ? 0 : reference.actions().size());
            map.put("triggerCount", reference.triggers() == null ? 0 : reference.triggers().size());
            map.put("protocolVersion", reference.protocolVersion());
            map.put("schemaVersion", reference.schemaVersion());
            return map;
        }
    }
}
