package com.zwbd.agentnexus.sdui.statemachine;

/**
 * Identifies what triggered a state machine run.
 */
public enum RunSource {
    MANUAL,
    DEVICE_EVENT,
    COMMAND_EVENT
}
