package com.virtualization.servicevirtualizationplatform.engine.template;

import com.virtualization.servicevirtualizationplatform.model.VirtualRequest;

import java.util.Map;

/**
 * Context object passed to TemplateResolver for placeholder resolution.
 * Created per-request — lives on the calling thread's stack.
 */
public class TemplateContext {

    private final VirtualRequest request;
    private final Map<String, String> pathVariables;
    private final String sessionId;
    private Map<String, Object> stateResult;
    private Map<String, Object> responseBody;

    public TemplateContext(VirtualRequest request, Map<String, String> pathVariables) {
        this.request = request;
        this.pathVariables = pathVariables;
        this.sessionId = request.sessionId();
    }

    public VirtualRequest request() { return request; }
    public Map<String, String> pathVariables() { return pathVariables; }
    public String sessionId() { return sessionId; }

    public Map<String, Object> stateResult() { return stateResult; }
    public void setStateResult(Map<String, Object> stateResult) { this.stateResult = stateResult; }

    public Map<String, Object> responseBody() { return responseBody; }
    public void setResponseBody(Map<String, Object> responseBody) { this.responseBody = responseBody; }
}
