package com.dwinovo.numen.agent.tool;

/**
 * One tool call as the model issued it — the single logical unit that flows
 * through the whole system. Its facets at each hop (the client handle
 * {@link ToolCall}, the request payload on the wire, the server task record,
 * the result payload back) are all tied together by {@link #id()}.
 *
 * @param id      the LLM's {@code tool_call} id — echoed back in the result
 * @param name    the tool name the model chose
 * @param argsJson the raw JSON arguments the model emitted
 */
public record ToolInvocation(String id, String name, String argsJson) {}
