package com.virtualization.servicevirtualizationplatform.model;

import java.util.Map;

public record RawTrafficLog(
        RequestData request,
        ResponseData response
) {
    public record RequestData(
            String protocol,    // HTTP, GRPC, KAFKA — defaults to HTTP if null
            String method,      // HTTP verb, gRPC method, or Kafka topic
            String path,        // HTTP path (empty for gRPC/Kafka)
            Map<String, String> headers,
            Map<String, Object> body
    ) {
        /**
         * Returns protocol, defaulting to HTTP if null/blank.
         */
        public String effectiveProtocol() {
            return (protocol == null || protocol.isBlank()) ? "HTTP" : protocol.toUpperCase();
        }
    }

    public record ResponseData(
            int statusCode,
            Map<String, String> headers,
            Map<String, Object> body
    ) {}

    // Convenience constructor for simple HTTP cases (backward compatible)
    public RawTrafficLog(String path, Map<String, Object> body) {
        this(new RequestData("HTTP", "POST", path, Map.of("Content-Type", "application/json"), body),
             new ResponseData(200, Map.of(), body));
    }
}
