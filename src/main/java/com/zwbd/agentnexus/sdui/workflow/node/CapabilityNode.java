package com.zwbd.agentnexus.sdui.workflow.node;

/**
 * Core abstraction for all workflow actions.
 * Replaces the hardcoded ActionDef sealed interface with an extensible plugin model.
 *
 * Implementations are discovered by CapabilityNodeRegistry via Spring component scan.
 * Platform nodes are singletons; device nodes are instantiated per device from its capability snapshot.
 */
public interface CapabilityNode {

    /** Globally unique type identifier, e.g. "device.audio.play", "platform.tts", "flow.condition" */
    String type();

    /** Declares the I/O contract for the frontend editor and runtime validation */
    NodeSchema schema();

    /** Execute this node in the given context */
    NodeResult execute(NodeContext ctx);
}
