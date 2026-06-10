package com.zwbd.agentnexus.sdui.workflow.node.device;

import com.zwbd.agentnexus.sdui.capability.CapabilityInvocationValidator;
import com.zwbd.agentnexus.sdui.service.CommandService;
import com.zwbd.agentnexus.sdui.service.PlatformCapabilityRuntimeService;
import com.zwbd.agentnexus.sdui.protocol.SduiProtocolConstants;
import com.zwbd.agentnexus.sdui.protocol.SduiRuntimeHandlers;
import com.zwbd.agentnexus.sdui.workflow.VariableResolver;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ControlNode implements CapabilityNode {

    private final CommandService commandService;
    private final CapabilityInvocationValidator invocationValidator;
    private final PlatformCapabilityRuntimeService platformRuntimeService;

    public ControlNode(CommandService commandService,
                       CapabilityInvocationValidator invocationValidator,
                       PlatformCapabilityRuntimeService platformRuntimeService) {
        this.commandService = commandService;
        this.invocationValidator = invocationValidator;
        this.platformRuntimeService = platformRuntimeService;
    }

    @Override
    public String type() { return "device.control"; }

    @Override
    public NodeSchema schema() {
        Map<String, Object> commandConstraints = new LinkedHashMap<>();
        commandConstraints.put("source", "device.commands");
        // options are injected per-device by CapabilityNodeRegistry.getDeviceSchemas()
        return new NodeSchema(type(), "设备命令", "向设备发送执行器命令",
                "device", "terminal",
                List.of(
                        new NodeSchema.ParamDef("command", "string", true, null,
                                "选择要执行的设备命令", commandConstraints),
                        new NodeSchema.ParamDef("params", "map", false, null,
                                "命令参数，根据所选命令动态填充")
                ),
                List.of(),
                false, 5000, null, "device", SduiProtocolConstants.NodeProtocols.COMMAND_CONTROL, SduiRuntimeHandlers.DEVICE_COMMAND,
                Map.of("commandSource", "resolved_contract.outputs"));
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String command = (String) ctx.resolvedInputs().get("command");
        if (command == null || command.isBlank()) {
            return NodeResult.error("Missing 'command' input");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> params = ctx.resolvedInputs().get("params") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();

        // Resolve variable expressions in param values
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        for (var entry : params.entrySet()) {
            resolvedParams.put(entry.getKey(), resolveValue(entry.getValue(), ctx));
        }

        // Unified validation — same path as POST /debug/{deviceId}/command
        CapabilityInvocationValidator.ValidationResult validation =
                invocationValidator.validateDebugInvocation(ctx.deviceId(), command, resolvedParams);
        if (!validation.valid()) {
            return NodeResult.error("Invalid command params: " + String.join("; ", validation.errors()));
        }

        // Platform capabilities route through the platform runtime (same as debug)
        if (platformRuntimeService.supports(command)) {
            Map<String, Object> result = platformRuntimeService.execute(
                    ctx.deviceId(), command, validation.normalizedParams());
            if ("ERROR".equals(result.get("status"))) {
                return NodeResult.error(String.valueOf(result.getOrDefault("error", "platform capability error")));
            }
            return NodeResult.completed(Map.of("command", command, "source", "platform", "result", result));
        }

        // Device command dispatch — same path as debug
        commandService.dispatchCommand(ctx.deviceId(), command, validation.normalizedParams());
        return NodeResult.completed(Map.of("command", command, "params", validation.normalizedParams()));
    }

    private Object resolveValue(Object expr, NodeContext ctx) {
        if (expr == null) return null;
        return VariableResolver.resolveValue(expr,
                ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env());
    }
}
