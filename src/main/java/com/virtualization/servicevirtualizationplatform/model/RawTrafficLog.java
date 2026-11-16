package com.virtualization.servicevirtualizationplatform.model;

import java.util.Map;

public record RawTrafficLog(
        RequestData request,
        ResponseData response
) {
    public record RequestData(
            String method,
            String path,
            Map<String, String> headers,
            Map<String, Object> body
    ) {}

    public record ResponseData(
            int statusCode,
            Map<String, String> headers,
            Map<String, Object> body
    ) {}

    // Convenience constructor for simple cases
    public RawTrafficLog(String path, Map<String, Object> body) {
        this(new RequestData("POST", path, Map.of("Content-Type", "application/json"), body),
             new ResponseData(200, Map.of(), body));
    }
}
