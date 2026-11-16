package com.virtualization.servicevirtualizationplatform.recording;

import com.virtualization.servicevirtualizationplatform.model.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Three-layer PCI-DSS/PII sanitization pipeline.
 * Layer 1: Field name check (cheapest — Set.contains)
 * Layer 2: Value type check (instanceof)
 * Layer 3: Value pattern matching (regex + Luhn)
 *
 * All regex patterns compiled once as static finals — zero per-request cost.
 * Favors RECALL over PRECISION: better to over-redact than miss a card number.
 */
@Service
public class PciPiiSanitizer {

    // ═══ STATIC FINAL COMPILED PATTERNS — compiled once at class load ═══

    private static final Pattern CARD_PATTERN = Pattern.compile("\\b([3-6]\\d{12,18})\\b");
    private static final Pattern CARD_WITH_SEPARATORS = Pattern.compile("\\b(\\d{4}[- ]\\d{4}[- ]\\d{4}[- ]\\d{4})\\b");
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b(\\d{3}-\\d{2}-\\d{4})\\b");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}\\b");
    private static final Pattern JWT_PATTERN = Pattern.compile("Bearer\\s+[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern API_KEY_PATTERN = Pattern.compile("\\b(sk_live_|pk_live_|tok_|api_key_)[A-Za-z0-9]{10,}\\b");

    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "credit_card", "card_number", "card_no", "cc_number", "pan", "card",
            "cvv", "cvc", "cvv2", "security_code",
            "ssn", "social_security", "social_security_number", "tax_id",
            "email", "email_address", "customer_email",
            "password", "passwd", "secret", "token", "auth_token",
            "authorization", "phone", "phone_number", "mobile"
    );

    private static final Set<String> KNOWN_SAFE_NAMES = Set.of(
            "amount", "currency", "status", "type", "name", "merchant",
            "description", "count", "total", "price", "quantity", "balance",
            "account_id", "payment_id", "order_id", "transaction_id", "merchant_id",
            "created_at", "updated_at", "timestamp", "region", "country",
            "card_type", "card_brand", "refund_id", "transfer_id", "customer_id",
            "username", "tier", "reason"
    );

    // Luhn lookup table for fast digit doubling
    private static final int[] LUHN_DOUBLES = {0, 2, 4, 6, 8, 1, 3, 5, 7, 9};

    // ═══ PUBLIC API ═══

    @SuppressWarnings("unchecked")
    public SanitizationResult sanitize(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return new SanitizationResult(Map.of(), emptySummary());
        }

        Map<String, Object> sanitized = new LinkedHashMap<>();
        List<FieldClassificationResult> details = new ArrayList<>();
        int pciMasked = 0, piiMasked = 0, safeKept = 0, unknown = 0, highRisk = 0;

        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Recursive for nested objects
            if (value instanceof Map) {
                SanitizationResult nested = sanitize((Map<String, Object>) value);
                sanitized.put(key, nested.sanitizedBody());
                SanitizationSummary ns = nested.summary();
                pciMasked += ns.pciFieldsMasked();
                piiMasked += ns.piiFieldsMasked();
                safeKept += ns.safeFieldsKept();
                unknown += ns.unknownFields();
                highRisk += ns.highRiskUnknowns();
                details.addAll(ns.fieldDetails());
                continue;
            }

            FieldClassificationResult result = classifyField(key, value);
            details.add(result);

            switch (result.classification()) {
                case PCI_CARD -> { sanitized.put(key, maskCard(String.valueOf(value))); pciMasked++; }
                case PCI_CVV -> { sanitized.put(key, "****"); pciMasked++; }
                case PCI_AUTH_TOKEN -> { sanitized.put(key, "****"); pciMasked++; }
                case PII_SSN -> { sanitized.put(key, maskSsn(String.valueOf(value))); piiMasked++; }
                case PII_EMAIL -> { sanitized.put(key, "****@masked.com"); piiMasked++; }
                case PII_PHONE -> { sanitized.put(key, "****"); piiMasked++; }
                case UNKNOWN -> {
                    // Conservative: redact unknowns that look high-risk
                    if (isHighRiskUnknown(value)) {
                        sanitized.put(key, "****");
                        highRisk++;
                    } else {
                        sanitized.put(key, value);
                    }
                    unknown++;
                }
                default -> { sanitized.put(key, value); safeKept++; }
            }
        }

        int total = pciMasked + piiMasked + safeKept + unknown;
        int classified = total - unknown;

        SanitizationSummary summary = new SanitizationSummary(
                total, classified, pciMasked, piiMasked, safeKept,
                unknown, highRisk, details);

        return new SanitizationResult(sanitized, summary);
    }

    public Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        if (headers == null) return Map.of();
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key.equalsIgnoreCase("Authorization") ||
                    key.equalsIgnoreCase("X-API-Key") ||
                    SENSITIVE_FIELD_NAMES.contains(key.toLowerCase())) {
                sanitized.put(key, "****");
            } else {
                sanitized.put(key, value);
            }
        }
        return sanitized;
    }

    // ═══ CLASSIFICATION — 4 layers ═══

    public FieldClassificationResult classifyField(String fieldName, Object value) {
        String lowerName = fieldName.toLowerCase();

        // LAYER 1: Field name check (cheapest)
        if (SENSITIVE_FIELD_NAMES.contains(lowerName)) {
            FieldClassification type = classifyByName(lowerName);
            return new FieldClassificationResult(fieldName, value, type, "MASKED", 1);
        }
        if (KNOWN_SAFE_NAMES.contains(lowerName)) {
            return new FieldClassificationResult(fieldName, value, FieldClassification.SAFE_ENUM, "KEPT", 1);
        }
        // Fuzzy name matching
        if (lowerName.contains("card") || lowerName.contains("cc_")) {
            return new FieldClassificationResult(fieldName, value, FieldClassification.PCI_CARD, "MASKED", 1);
        }
        if (lowerName.contains("social") || lowerName.contains("ssn")) {
            return new FieldClassificationResult(fieldName, value, FieldClassification.PII_SSN, "MASKED", 1);
        }
        if (lowerName.contains("email")) {
            return new FieldClassificationResult(fieldName, value, FieldClassification.PII_EMAIL, "MASKED", 1);
        }

        // LAYER 2: Value type check
        if (value == null) {
            return new FieldClassificationResult(fieldName, null, FieldClassification.SAFE_ENUM, "KEPT", 2);
        }
        if (value instanceof Number) {
            return new FieldClassificationResult(fieldName, value, FieldClassification.SAFE_NUMBER, "KEPT", 2);
        }
        if (value instanceof Boolean) {
            return new FieldClassificationResult(fieldName, value, FieldClassification.SAFE_BOOLEAN, "KEPT", 2);
        }

        // LAYER 3: Value pattern matching (regex + Luhn) — strings only
        if (value instanceof String strVal) {
            // Credit card check
            String digitsOnly = strVal.replaceAll("[\\s-]", "");
            if (digitsOnly.length() >= 13 && digitsOnly.length() <= 19 &&
                    digitsOnly.matches("\\d+") && luhnCheck(digitsOnly)) {
                return new FieldClassificationResult(fieldName, value, FieldClassification.PCI_CARD, "MASKED", 3);
            }

            // SSN pattern
            if (SSN_PATTERN.matcher(strVal).find()) {
                return new FieldClassificationResult(fieldName, value, FieldClassification.PII_SSN, "MASKED", 3);
            }

            // Email pattern
            if (EMAIL_PATTERN.matcher(strVal).find()) {
                return new FieldClassificationResult(fieldName, value, FieldClassification.PII_EMAIL, "MASKED", 3);
            }

            // JWT/Bearer token
            if (JWT_PATTERN.matcher(strVal).find() || API_KEY_PATTERN.matcher(strVal).find()) {
                return new FieldClassificationResult(fieldName, value, FieldClassification.PCI_AUTH_TOKEN, "MASKED", 3);
            }
        }

        // LAYER 4: Heuristic safe check
        if (value instanceof String strVal) {
            if (strVal.length() <= 20 && strVal.matches("[A-Za-z][A-Za-z ]*")) {
                return new FieldClassificationResult(fieldName, value, FieldClassification.SAFE_ENUM, "KEPT", 4);
            }
            if (strVal.matches("[A-Z]{2,5}-\\d+")) {
                return new FieldClassificationResult(fieldName, value, FieldClassification.SAFE_ID, "KEPT", 4);
            }
        }

        return new FieldClassificationResult(fieldName, value, FieldClassification.UNKNOWN, "UNKNOWN", 4);
    }

    // ═══ HELPERS ═══

    private FieldClassification classifyByName(String lowerName) {
        if (lowerName.contains("card") || lowerName.equals("pan")) return FieldClassification.PCI_CARD;
        if (lowerName.contains("cvv") || lowerName.contains("cvc") || lowerName.contains("security_code"))
            return FieldClassification.PCI_CVV;
        if (lowerName.contains("ssn") || lowerName.contains("social")) return FieldClassification.PII_SSN;
        if (lowerName.contains("email")) return FieldClassification.PII_EMAIL;
        if (lowerName.contains("phone") || lowerName.contains("mobile")) return FieldClassification.PII_PHONE;
        return FieldClassification.PCI_AUTH_TOKEN;
    }

    public static boolean luhnCheck(String number) {
        int sum = 0;
        boolean alternate = false;
        for (int i = number.length() - 1; i >= 0; i--) {
            int digit = number.charAt(i) - '0';
            if (digit < 0 || digit > 9) return false;
            if (alternate) {
                digit = LUHN_DOUBLES[digit];
            }
            sum += digit;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    private String maskCard(String value) {
        String digits = value.replaceAll("[\\s-]", "");
        if (digits.length() >= 4) {
            return "****" + digits.substring(digits.length() - 4);
        }
        return "****";
    }

    private String maskSsn(String value) {
        // 123-45-6789 → ****6789
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() >= 4) {
            return "****" + digits.substring(digits.length() - 4);
        }
        return "****";
    }

    private boolean isHighRiskUnknown(Object value) {
        if (!(value instanceof String s)) return false;
        if (s.length() >= 10 && s.matches(".*\\d{6,}.*")) return true;
        if (s.length() >= 20 && s.matches("[A-Za-z0-9+/=_-]+")) return true;
        return false;
    }

    private SanitizationSummary emptySummary() {
        return new SanitizationSummary(0, 0, 0, 0, 0, 0, 0, List.of());
    }
}
