package com.zwbd.agentnexus.sdui.workflow;

import java.util.List;

public record VirtualIO(
        String id,
        String label,
        String capability,
        List<String> preferred
) {}
