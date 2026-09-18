package com.zwbd.agentnexus.sdui.v2.protocol;

/**
 * v2 控制面消息名称常量。
 *
 * <p>终端设计文档只给出语义与示例名称（04_PROTOCOL_MODEL.md §5 / §8.1），未逐字固定拼写。
 * 这里集中声明以便一处对齐终端，见缺口 G18。注意两处易混名称：</p>
 *
 * <pre>
 * platform.interaction         终端 → 平台的主动事件（无 id）
 * platform.interaction.report  本地响应序列中的一个动作，由平台配置 token
 * </pre>
 */
public final class V2Names {

    private V2Names() {}

    // ── 业务面（01_INTERACTION_MODEL.md §6、04_PROTOCOL_MODEL.md §5）──
    public static final String BUSINESS_RESET = "business.reset";
    public static final String BUSINESS_UPDATE = "business.update";
    public static final String BUSINESS_TRIGGER = "business.trigger";

    // ── 平台交互 ──
    public static final String PLATFORM_INTERACTION = "platform.interaction";
    public static final String ACTION_PLATFORM_REPORT = "platform.interaction.report";

    // ── 能力同步（缺口 G7）──
    public static final String CAPABILITY_GET = "capability.get";
    public static final String DEVICE_HELLO = "device.hello";

    // ── 显示（03_UI_MODEL.md、04_PROTOCOL_MODEL.md §8.1）──
    public static final String DISPLAY_SECTION = "display.section";
    public static final String DISPLAY_IMAGE_BEGIN = "display.image.begin";
    public static final String DISPLAY_IMAGE_END = "display.image.end";
    public static final String DISPLAY_CANVAS_OPEN = "display.canvas.open";
    public static final String DISPLAY_CANVAS_CLOSE = "display.canvas.close";

    // ── 音频（04_PROTOCOL_MODEL.md §7）──
    public static final String AUDIO_START = "audio.start";
    public static final String AUDIO_STOP = "audio.stop";
    public static final String AUDIO_ABORT = "audio.abort";

    // ── 系统命令（02_SYSTEM_BOUNDARY.md §4、04_PROTOCOL_MODEL.md §5）──
    public static final String SYSTEM_VOLUME_SET = "system.volume.set";
    public static final String SYSTEM_BRIGHTNESS_SET = "system.brightness.set";
    public static final String SYSTEM_REBOOT = "system.reboot";
    public static final String SYSTEM_PROVISIONING_START = "system.provisioning.start";
}
