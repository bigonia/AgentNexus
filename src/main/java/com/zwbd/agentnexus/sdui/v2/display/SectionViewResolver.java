package com.zwbd.agentnexus.sdui.v2.display;

import com.zwbd.agentnexus.sdui.section.SectionData;
import com.zwbd.agentnexus.sdui.section.SectionDataCodec;
import com.zwbd.agentnexus.sdui.section.SectionEntry;
import com.zwbd.agentnexus.sdui.section.SectionPatch;
import com.zwbd.agentnexus.sdui.section.SectionScene;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把平台侧的富 UI 产物收敛为 v2 主视图的<b>单个完整 Section</b>。
 *
 * <h2>为什么需要这一层</h2>
 * <p>v2 的主视图模型（03_UI_MODEL.md §1–§2）与旧模型有三处不同：</p>
 * <ul>
 *   <li>同一时间只有<b>一个</b> Section 独占屏幕，不拼接、不嵌套；</li>
 *   <li>更新采用<b>完整替换</b>，不提供 Section 级 Patch；</li>
 *   <li>没有 page、没有 layout、没有渲染模式。</li>
 * </ul>
 * <p>平台侧的 UI 上下文仍按"页面 → 多个 Section"组织，因此每次下发前都要发生一次收敛。把收敛规则
 * 集中在这里，是为了让"取哪个 Section""Patch 如何合成"只有一个真值——Section Schema 定稿
 * （03§2.1 的五类与准确字段尚待确定）后只改本类，不必翻遍所有下发点。</p>
 *
 * <h2>两种入口，一条规则</h2>
 * <p>工作流节点与 v2 请求的参数是 JSON 形态，平台内部既有实现持有类型化的
 * {@link SectionScene} / {@link SectionPatch}。两套入口都归一到同一个内部表示——
 * {@code {sectionId, sectionType, fields}}——再执行同一套收敛规则，避免两条路径各自演化。</p>
 *
 * <h2>不做什么</h2>
 * <p>不做 Section 类型的能力门禁。终端声明的 {@code ui.sectionTypes} 属于
 * {@code CapabilitySchemaV2} 的校验职责，在这里再判断一次会形成第二处真值。</p>
 */
@Slf4j
@Component
public class SectionViewResolver {

    private final SectionDataCodec sectionDataCodec;

    /**
     * 平台侧维护的"当前主视图 Section"。
     *
     * <p>v2 没有 Patch，补丁必须在平台侧合成为完整 Section 再下发。这份快照既是合成的基底，
     * 也是"平台最后下发了什么"的真值。设备断开或业务清理时应丢弃，见 {@link #forget}。</p>
     */
    private final Map<String, Map<String, Object>> currentByDevice = new ConcurrentHashMap<>();

    public SectionViewResolver(SectionDataCodec sectionDataCodec) {
        this.sectionDataCodec = sectionDataCodec;
    }

    // ── 主视图收敛：Scene → 单 Section ──────────────────────────────────────

    /**
     * 从类型化 Scene 取主视图。
     *
     * <p>取声明顺序上的<b>首个</b> Section。设计把"一次下发一个完整 Section"作为唯一形态，而平台
     * 侧模板仍可能声明多个；首屏语义是这里唯一自洽的选择。若某模板确实需要多屏，应在模板层拆成
     * 多个模板，而不是在本类里做选择。</p>
     */
    public Optional<Map<String, Object>> primaryOf(SectionScene scene) {
        if (scene == null || scene.sections() == null || scene.sections().isEmpty()) {
            return Optional.empty();
        }
        List<SectionEntry> sections = scene.sections();
        if (sections.size() > 1) {
            log.info("Scene 声明了 {} 个 Section，v2 主视图只取首个: pageId={}, kept={}",
                    sections.size(), scene.pageId(), sections.get(0).sectionId());
        }
        return Optional.of(toSection(sections.get(0)));
    }

    /**
     * 从 JSON 形态的 Scene 取主视图。
     *
     * @param scene {@code {pageId, layout, sections:[{sectionId, sectionType, fields}]}}
     */
    public Optional<Map<String, Object>> primaryOfScene(Map<String, Object> scene) {
        List<Map<String, Object>> sections = sectionsOf(scene);
        if (sections.isEmpty()) {
            return Optional.empty();
        }
        if (sections.size() > 1) {
            log.info("Scene 声明了 {} 个 Section，v2 主视图只取首个: pageId={}, kept={}",
                    sections.size(), scene.get("pageId"), sections.get(0).get("sectionId"));
        }
        return Optional.of(normalize(sections.get(0)));
    }

    // ── 主视图收敛：Patch → 完整 Section ────────────────────────────────────

    /**
     * 把类型化 Patch 应用到当前主视图，产出完整 Section。
     *
     * <p>无当前主视图时返回空——补丁是增量，没有基底就不该猜。调用方此时应改为下发完整 Section。</p>
     */
    public Optional<Map<String, Object>> applyPatch(String deviceId, SectionPatch patch) {
        if (patch == null || patch.patches() == null || patch.patches().isEmpty()) {
            return current(deviceId);
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (SectionPatch.PatchEntry entry : patch.patches()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sectionId", entry.sectionId());
            item.put("op", entry.op());
            item.put("sectionType", entry.type());
            item.put("fields", entry.data() == null ? Map.of() : sectionDataCodec.toFieldMap(entry.data()));
            entries.add(item);
        }
        return applyPatchEntries(deviceId, entries);
    }

    /**
     * 把 JSON 形态的 Patch 应用到当前主视图。
     *
     * @param patch {@code {pageId, patches:[{sectionId, op, sectionType, fields}]}}
     */
    public Optional<Map<String, Object>> applyPatchJson(String deviceId, Map<String, Object> patch) {
        if (patch == null) {
            return current(deviceId);
        }
        Object raw = patch.get("patches");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return current(deviceId);
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object item : list) {
            entries.add(asMap(item));
        }
        return applyPatchEntries(deviceId, entries);
    }

    private Optional<Map<String, Object>> applyPatchEntries(String deviceId, List<Map<String, Object>> entries) {
        Map<String, Object> current = currentByDevice.get(deviceId);
        if (current == null) {
            log.debug("无当前主视图快照，无法在平台侧合成 Patch: device={}", deviceId);
            return Optional.empty();
        }

        Map<String, Object> merged = new LinkedHashMap<>(current);
        for (Map<String, Object> entry : entries) {
            String sectionId = string(entry.get("sectionId"));
            String op = string(entry.get("op"));
            boolean targetsPrimary = sectionId != null && sectionId.equals(merged.get("sectionId"));

            if ("remove".equals(op)) {
                if (targetsPrimary) {
                    // 主视图被移除，此后没有主视图可下发
                    return Optional.empty();
                }
                log.info("Patch 移除的 Section 不是当前主视图，已忽略: device={}, sectionId={}", deviceId, sectionId);
                continue;
            }

            if (targetsPrimary) {
                Map<String, Object> fields = new LinkedHashMap<>(asMap(merged.get("fields")));
                fields.putAll(asMap(entry.get("fields")));
                Map<String, Object> updated = new LinkedHashMap<>(merged);
                updated.put("fields", fields);
                merged = updated;
            } else {
                // v2 只有单个 Section：补丁目标不是当前主视图时，语义上等于"换一份主视图"，
                // 直接替换而不是报错——否则一次切屏会被当成配置错误。
                merged = normalize(entry);
            }
        }
        return Optional.of(merged);
    }

    // ── 当前主视图快照 ──────────────────────────────────────────────────────

    /** 记住设备当前主视图。下发成功后调用，作为后续 Patch 合成的基底。 */
    public void remember(String deviceId, Map<String, Object> section) {
        if (deviceId == null) {
            return;
        }
        if (section == null || section.isEmpty()) {
            currentByDevice.remove(deviceId);
            return;
        }
        currentByDevice.put(deviceId, normalize(section));
    }

    public Optional<Map<String, Object>> current(String deviceId) {
        return Optional.ofNullable(currentByDevice.get(deviceId));
    }

    /**
     * 丢弃平台侧主视图快照。
     *
     * <p>设备断开、业务清理（{@code business.reset}）后调用。快照只是"平台最后一次下发了什么"，
     * 终端在断开期可能已回到系统界面，继续拿它当基底合成 Patch 会产生错位的主视图。</p>
     */
    public void forget(String deviceId) {
        if (deviceId != null) {
            currentByDevice.remove(deviceId);
        }
    }

    // ── 转换 ────────────────────────────────────────────────────────────────

    public Map<String, Object> toSection(SectionEntry entry) {
        return toSection(entry.sectionId(), entry.type(), entry.data());
    }

    /**
     * Section 的 wire 形式，也是内部统一表示。
     *
     * <p>与平台侧 UI 上下文里 Section 的存法保持一致（{@code sectionId} / {@code sectionType} /
     * {@code fields}），这样模板、上下文和下发三处不必各自做一次字段翻译。字段名属缺口 G13，
     * 终端实现完成后在此处一次对齐。</p>
     */
    public Map<String, Object> toSection(String sectionId, String sectionType, SectionData data) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("sectionId", sectionId);
        section.put("sectionType", sectionType);
        section.put("fields", data == null ? Map.of() : sectionDataCodec.toFieldMap(data));
        return section;
    }

    /** 只保留主视图需要的三个字段，丢弃 page/layout 等 v2 不存在的概念。 */
    private static Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("sectionId", raw.get("sectionId"));
        Object type = raw.get("sectionType") != null ? raw.get("sectionType") : raw.get("type");
        section.put("sectionType", type);
        section.put("fields", asMap(raw.get("fields")));
        return section;
    }

    private static List<Map<String, Object>> sectionsOf(Map<String, Object> scene) {
        if (scene == null) {
            return List.of();
        }
        Object raw = scene.get("sections");
        List<Map<String, Object>> sections = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                sections.add(asMap(item));
            }
        } else if (raw instanceof Map<?, ?> map) {
            // UI 上下文把 sections 存成 id → section 的映射
            for (Object item : map.values()) {
                sections.add(asMap(item));
            }
        }
        return sections;
    }

    private static Map<String, Object> asMap(Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                map.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return map;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
