package com.zwbd.agentnexus.sdui.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

public class WorkflowInstance {

    public enum Status { RUNNING, STOPPED }

    private final String deviceId;
    private final String workflowId;
    private final Map<String, Object> variables;
    private final VariableWatcher watcher;
    private String activePage;
    private Status status;

    public WorkflowInstance(String deviceId, String workflowId) {
        this.deviceId = deviceId;
        this.workflowId = workflowId;
        this.variables = new LinkedHashMap<>();
        this.watcher = new VariableWatcher();
        this.status = Status.RUNNING;
    }

    public String deviceId() { return deviceId; }
    public String workflowId() { return workflowId; }
    public Map<String, Object> variables() { return variables; }
    public VariableWatcher watcher() { return watcher; }
    public String activePage() { return activePage; }
    public void activePage(String page) { this.activePage = page; }
    public Status status() { return status; }
    public void status(Status s) { this.status = s; }

    public void putVariable(String key, Object value) {
        variables.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> variablesAsMap() {
        return (Map<String, Object>) variables;
    }
}
