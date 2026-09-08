package com.feng.medical.streaming;

/** Stable event names written to the run journal and exposed over SSE. */
public enum RunEventType {
    RUN_STARTED("run.started"),
    NODE_STARTED("node.started"),
    NODE_COMPLETED("node.completed"),
    NODE_FAILED("node.failed"),
    ROUTE_SELECTED("route.selected"),
    RETRIEVAL_COMPLETED("retrieval.completed"),
    TOOL_STARTED("tool.started"),
    TOOL_COMPLETED("tool.completed"),
    TOOL_FAILED("tool.failed"),
    ANSWER_DELTA("answer.delta"),
    CITATIONS("run.citations"),
    CITATION_READY("citation.ready"),
    USAGE_UPDATED("usage.updated"),
    SUMMARY("run.summary"),
    RUN_COMPLETED("run.completed"),
    RUN_FAILED("run.failed"),
    RUN_CANCELLED("run.cancelled");

    private final String wireName;
    RunEventType(String wireName) { this.wireName = wireName; }
    public String wireName() { return wireName; }
    public boolean isTerminal() {
        return this == RUN_COMPLETED || this == RUN_FAILED || this == RUN_CANCELLED;
    }
    public static RunEventType fromWireName(String wireName) {
        for (RunEventType value : values()) if (value.wireName.equals(wireName)) return value;
        throw new IllegalArgumentException("不支持的运行事件类型");
    }
}
