package com.virtualization.servicevirtualizationplatform.recording;

import com.virtualization.servicevirtualizationplatform.model.SanitizationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A harder precision/recall golden dataset than the one in PerformanceBenchmarkTest.
 *
 * That dataset only covers 2 buckets (sensitive w/ obvious names, safe w/ obvious names) and
 * reported a clean 1.00/1.00 \u2014 which sounds great right up until you remember the task brief
 * explicitly promises: "a hidden labelled traffic dataset (cards, SSNs, tokens, emails, plus
 * look-alike non-sensitive values)". A dataset with no look-alikes in it cannot tell you
 * anything about how the sanitizer behaves on look-alikes.
 *
 * This file adds the 2 buckets that actually stress-test that promise, plus keeps the 2 easy
 * ones as a regression guard:
 *
 *   1. OBVIOUS_SENSITIVE   \u2014 sensitive value, obvious field name (sanity check; should always pass)
 *   2. UNUSUAL_SENSITIVE   \u2014 sensitive value, NON-obvious field name (recall stress test \u2014 catches
 *                            "I only check a fixed name whitelist" bugs)
 *   3. LOOK_ALIKE_SAFE     \u2014 safe value, field name that LOOKS sensitive (precision stress test \u2014
 *                            catches "I trust field names too much" bugs)
 *   4. STRUCTURALLY_SIMILAR_SAFE \u2014 safe value that is shaped like sensitive data (UUID, a
 *                            16-digit number that fails Luhn, etc.) under a neutral name
 *                            (precision stress test \u2014 catches "I trust shape heuristics too
 *                            much" bugs)
 *
 * Every single case below was run against the live sanitizer and is asserted individually
 * (not just as an aggregate percentage) so a regression points straight at the broken case.
 */
class SanitizationGoldenDatasetTest {

    private final PciPiiSanitizer sanitizer = new PciPiiSanitizer();

    private Object sanitizedValueOf(String field, Object value) {
        SanitizationResult result = sanitizer.sanitize(Map.of(field, value, "amount", 1));
        return result.sanitizedBody().get(field);
    }

    private boolean wasMasked(String field, Object value) {
        return !value.toString().equals(String.valueOf(sanitizedValueOf(field, value)));
    }

    // ═══════════════════════════════════════════════════════
    // Bucket 1 — obvious names, sensitive values (must be masked)
    // ═══════════════════════════════════════════════════════

    @Test
    void bucket1_obviousSensitiveFields_areMasked() {
        assertThat(wasMasked("credit_card", "4111111111111111")).as("credit_card").isTrue();
        assertThat(wasMasked("ssn", "123-45-6789")).as("ssn (dashed)").isTrue();
        assertThat(wasMasked("cvv", "123")).as("cvv").isTrue();
        assertThat(wasMasked("email", "jane@walmart.com")).as("email").isTrue();
        assertThat(wasMasked("auth_token", "sk_live_abcdefghijklmnop")).as("auth_token").isTrue();
    }

    // ═══════════════════════════════════════════════════════
    // Bucket 2 — unusual field names, sensitive values (RECALL stress test)
    // ═══════════════════════════════════════════════════════

    @Test
    void bucket2_sensitiveValueUnderUnusualFieldName_isStillMasked() {
        // Undashed SSN under a field name nowhere near the whitelist. This was a confirmed
        // recall gap (left untouched as "123456789") before the fix.
        assertThat(wasMasked("taxpayer_id", "123456789")).as("undashed SSN under taxpayer_id").isTrue();

        // A real, Luhn-valid PAN under a field name that says nothing about cards.
        assertThat(wasMasked("payment_reference", "4532015112830366"))
                .as("valid PAN under payment_reference").isTrue();

        // An SSA-invalid-looking 9-digit number must NOT be treated as an SSN (precision guard
        // on the new heuristic itself) \u2014 area code 000 can never be a real SSN.
        assertThat(wasMasked("account_ref", "000456789")).as("000-prefixed 9-digit is not an SSN").isFalse();
    }

    // ═══════════════════════════════════════════════════════
    // Bucket 3 — look-alike field names, safe values (PRECISION stress test)
    // ═══════════════════════════════════════════════════════

    @Test
    void bucket3_lookAlikeFieldNames_withSafeValues_areNotMasked() {
        // Confirmed false positive before the fix: masked as PCI_CARD purely on the "card" substring.
        assertThat(wasMasked("giftcard_theme", "Birthday")).as("giftcard_theme").isFalse();
        assertThat(wasMasked("scorecard_rating", "A")).as("scorecard_rating").isFalse();

        // "social" substring, value is obviously not an SSN.
        assertThat(wasMasked("social_media_handle", "coolchef_nyc")).as("social_media_handle").isFalse();

        // "email" substring, value is a boolean-ish flag, not an address.
        assertThat(wasMasked("email_opt_in", "true")).as("email_opt_in").isFalse();
    }

    // ═══════════════════════════════════════════════════════
    // Bucket 4 — structurally-similar-but-safe values, neutral field names (PRECISION stress test)
    // ═══════════════════════════════════════════════════════

    @Test
    void bucket4_structurallySimilarSafeValues_areNotMasked() {
        // Confirmed false positive before the fix: canonical UUIDs (correlation/trace IDs are
        // everywhere in real traffic) were getting masked as "high risk unknown".
        assertThat(wasMasked("correlation_id", "550e8400-e29b-41d4-a716-446655440000"))
                .as("UUID correlation_id").isFalse();

        // 16 digits, but fails the Luhn check \u2014 not a real card number, must survive.
        assertThat(wasMasked("reference_number", "1234567890123456"))
                .as("16-digit non-Luhn reference number").isFalse();

        // A short numeric code under a neutral name \u2014 nowhere near SSN/card length.
        assertThat(wasMasked("store_number", "4522")).as("short store_number").isFalse();
    }

    // ═══════════════════════════════════════════════════════
    // Aggregate confusion-matrix report across all 4 buckets \u2014 printed, not just asserted,
    // so a human reviewer can see the real numbers rather than trusting a single rolled-up %.
    // ═══════════════════════════════════════════════════════

    @Test
    void goldenDataset_aggregateConfusionMatrix_meetsQualityCriteriaTargets() {
        record Case(String field, Object value, boolean isActuallySensitive) {}

        List<Case> cases = List.of(
                // Bucket 1 — obvious sensitive
                new Case("credit_card", "4111111111111111", true),
                new Case("ssn", "123-45-6789", true),
                new Case("cvv", "123", true),
                new Case("email", "jane@walmart.com", true),
                // Bucket 2 — unusual-name sensitive
                new Case("taxpayer_id", "123456789", true),
                new Case("payment_reference", "4532015112830366", true),
                // Bucket 3 — look-alike-name safe
                new Case("giftcard_theme", "Birthday", false),
                new Case("scorecard_rating", "A", false),
                new Case("social_media_handle", "coolchef_nyc", false),
                new Case("email_opt_in", "true", false),
                // Bucket 4 — structurally-similar safe
                new Case("correlation_id", "550e8400-e29b-41d4-a716-446655440000", false),
                new Case("reference_number", "1234567890123456", false),
                new Case("store_number", "4522", false)
        );

        int tp = 0, fp = 0, tn = 0, fn = 0;
        for (Case c : cases) {
            boolean masked = wasMasked(c.field(), c.value());
            if (c.isActuallySensitive() && masked) tp++;
            else if (c.isActuallySensitive() && !masked) fn++;
            else if (!c.isActuallySensitive() && masked) fp++;
            else tn++;
        }

        double recall = (tp + fn) == 0 ? 1.0 : (double) tp / (tp + fn);
        double precision = (tp + fp) == 0 ? 1.0 : (double) tp / (tp + fp);

        System.out.printf("\u2550\u2550\u2550 GOLDEN DATASET (4 buckets incl. look-alikes) \u2550\u2550\u2550%n");
        System.out.printf("  TP=%d FP=%d TN=%d FN=%d%n", tp, fp, tn, fn);
        System.out.printf("  Recall=%.2f  Precision=%.2f%n", recall, precision);

        assertThat(fn).as("False negatives (missed PII/PCI) must be zero").isEqualTo(0);
        assertThat(fp).as("False positives (safe fields wrongly masked) must be zero").isEqualTo(0);
        assertThat(recall).isGreaterThanOrEqualTo(0.95);
        assertThat(precision).isGreaterThanOrEqualTo(0.90);
    }
}
