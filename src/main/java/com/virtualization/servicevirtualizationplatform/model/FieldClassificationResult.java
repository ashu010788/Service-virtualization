package com.virtualization.servicevirtualizationplatform.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public record FieldClassificationResult(
        String fieldName,
        Object originalValue,
        FieldClassification classification,
        @JsonIgnore String action,
        int detectedByLayer
) {
    @JsonProperty("originalValue")
    public Object safeOriginalValue() {
        if (!"MASKED".equals(action)) {
            return originalValue;
        }
        return switch (classification) {
            case PCI_CARD -> maskCard(String.valueOf(originalValue));
            case PCI_CVV -> "****";
            case PCI_AUTH_TOKEN -> "****";
            case PII_SSN -> maskSsn(String.valueOf(originalValue));
            case PII_EMAIL -> "****@masked.com";
            case PII_PHONE -> "****";
            default -> "****";
        };
    }

    private String maskCard(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() >= 4) {
            return "****" + digits.substring(digits.length() - 4);
        }
        return "****";
    }

    private String maskSsn(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() >= 4) {
            return "****" + digits.substring(digits.length() - 4);
        }
        return "****";
    }
}
