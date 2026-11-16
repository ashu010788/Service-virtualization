package com.virtualization.servicevirtualizationplatform.engine.template;

import com.virtualization.servicevirtualizationplatform.engine.state.SessionStateStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {{...}} placeholders in response templates.
 * Stateless — creates new objects per call, never modifies the template.
 *
 * Supported placeholders:
 *   {{request.body.fieldName}}      — echo request body field
 *   {{request.pathVar.varName}}     — path variable from Trie (e.g. {id} in URL pattern)
 *   {{request.header.headerName}}   — request header value
 *   {{generate.id:PREFIX}}          — sequential ID (per-session counter)
 *   {{generate.uuid}}               — random UUID
 *   {{generate.timestamp}}          — current ISO timestamp
 *   {{generate.token}}              — random token string
 *   {{state.result}}                — entire state READ result
 *   {{state.result.fieldName}}      — specific field from state result
 *   {{response.body}}               — entire response body (for state actions)
 *   {{response.body.fieldName}}     — field from response body
 */
@Service
public class TemplateResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(.+?)}}");

    private final SessionStateStore stateStore;

    public TemplateResolver(SessionStateStore stateStore) {
        this.stateStore = stateStore;
    }

    /**
     * Resolve all placeholders in a body template map.
     * Returns a NEW map — never modifies the original template.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveBody(Map<String, Object> template, TemplateContext ctx) {
        if (template == null) return Map.of();

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                resolved.put(entry.getKey(), resolveString(s, ctx));
            } else if (value instanceof Map) {
                resolved.put(entry.getKey(), resolveBody((Map<String, Object>) value, ctx));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    /**
     * Resolve a single string expression (e.g. for state action key/value).
     */
    public String resolveToString(String expression, TemplateContext ctx) {
        if (expression == null) return null;
        Object result = resolveString(expression, ctx);
        return result != null ? String.valueOf(result) : null;
    }

    /**
     * Resolve value expression — can return Map or String depending on template.
     */
    @SuppressWarnings("unchecked")
    public Object resolveValue(Object valueExpr, TemplateContext ctx) {
        if (valueExpr instanceof String s) {
            return resolveString(s, ctx);
        } else if (valueExpr instanceof Map) {
            return resolveBody((Map<String, Object>) valueExpr, ctx);
        }
        return valueExpr;
    }

    // ═══ INTERNAL ═══

    private Object resolveString(String template, TemplateContext ctx) {
        if (!template.contains("{{")) return template;

        // If the ENTIRE value is one placeholder, return the raw object (preserves types)
        if (template.startsWith("{{") && template.endsWith("}}")
                && template.indexOf("{{", 2) == -1) {
            String token = template.substring(2, template.length() - 2);
            return resolveToken(token, ctx);
        }

        // Mixed text + placeholders → string concatenation
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Object resolved = resolveToken(matcher.group(1), ctx);
            matcher.appendReplacement(sb,
                    java.util.regex.Matcher.quoteReplacement(String.valueOf(resolved)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private Object resolveToken(String token, TemplateContext ctx) {
        String[] parts = token.split("\\.", 2);
        if (parts.length < 2) return "{{" + token + "}}";

        return switch (parts[0]) {
            case "request" -> resolveRequest(parts[1], ctx);
            case "generate" -> resolveGenerate(parts[1], ctx);
            case "state" -> resolveState(parts[1], ctx);
            case "response" -> resolveResponse(parts[1], ctx);
            default -> "{{" + token + "}}";
        };
    }

    private Object resolveRequest(String path, TemplateContext ctx) {
        String[] parts = path.split("\\.", 2);
        return switch (parts[0]) {
            case "body" -> parts.length > 1
                    ? navigateMap(ctx.request().body(), parts[1])
                    : ctx.request().body();
            case "pathVar" -> parts.length > 1
                    ? ctx.pathVariables().get(parts[1])
                    : ctx.pathVariables();
            case "header" -> parts.length > 1
                    ? ctx.request().headers().get(parts[1])
                    : ctx.request().headers();
            default -> null;
        };
    }

    private Object resolveGenerate(String spec, TemplateContext ctx) {
        if (spec.startsWith("id:")) {
            String prefix = spec.substring(3);
            long seq = stateStore.increment(ctx.sessionId(), "_counters", prefix);
            return prefix + "-" + String.format("%04d", seq);
        }
        return switch (spec) {
            case "uuid" -> UUID.randomUUID().toString();
            case "timestamp" -> Instant.now().toString();
            case "token" -> "tok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            default -> "{{generate." + spec + "}}";
        };
    }

    private Object resolveState(String path, TemplateContext ctx) {
        if ("result".equals(path)) return ctx.stateResult();
        if (path.startsWith("result.")) {
            return navigateMap(ctx.stateResult(), path.substring(7));
        }
        return null;
    }

    private Object resolveResponse(String path, TemplateContext ctx) {
        if ("body".equals(path)) return ctx.responseBody();
        if (path.startsWith("body.")) {
            return navigateMap(ctx.responseBody(), path.substring(5));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object navigateMap(Map<String, Object> map, String path) {
        if (map == null) return null;
        String[] parts = path.split("\\.");
        Object current = map;
        for (String part : parts) {
            if (current instanceof Map<?, ?> m) {
                current = m.get(part);
            } else {
                return null;
            }
        }
        return current;
    }
}
