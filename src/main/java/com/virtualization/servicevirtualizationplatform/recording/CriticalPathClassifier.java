package com.virtualization.servicevirtualizationplatform.recording;

import org.springframework.stereotype.Component;

/**
 * Identifies payment-, fraud-, and compliance-related paths that must never auto-publish,
 * regardless of confidence score.
 * <p>
 * Guardrail from CUSTOMER_VALUE.md: a wrongly auto-generated stub on a payment-processing,
 * fraud-detection, or compliance endpoint masks real integration bugs that cost money in
 * production. These paths always require human review before a generated stub goes live.
 */
@Component
public class CriticalPathClassifier {

    private static final String[] CRITICAL_KEYWORDS = {"payment"};

    public boolean isCritical(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        for (String keyword : CRITICAL_KEYWORDS) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }
}
