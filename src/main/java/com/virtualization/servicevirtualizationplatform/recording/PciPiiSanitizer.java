package com.virtualization.servicevirtualizationplatform.recording;

import com.virtualization.servicevirtualizationplatform.model.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Four-layer PCI-DSS/PII sanitization pipeline.
 * Layer 1: Field name check (cheapest — Set.contains, plus guarded fuzzy-name fallback)
 * Layer 2: Value type check (instanceof — null/Number/Boolean are automatically safe)
 * Layer 3: Value pattern matching (regex + Luhn, strings only)
 * Layer 4: Heuristic safe-shape check, then high-risk-unknown fallback for anything left
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
    private static final Pattern SSN_UNFORMATTED_PATTERN = Pattern.compile("^\\d{9}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}\\b");
    private static final Pattern JWT_PATTERN = Pattern.compile("Bearer\\s+[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern API_KEY_PATTERN = Pattern.compile("\\b(sk_live_|pk_live_|tok_|api_key_)[A-Za-z0-9]{10,}\\b");
    // Raw JWT with no "Bearer " scheme prefix (apps frequently store just the token value).
    // Each of the 3 dot-separated segments must be >=10 chars — strong enough to not trip on
    // short dotted look-alikes (version strings like "1.2.3", domains like "a.b.co").
    private static final Pattern BARE_JWT_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}$");
    // A card-shaped digit run (13-19 digits) optionally grouped by single spaces/dashes,
    // ANYWHERE inside a longer string — used to catch card numbers embedded in free-text notes.
    // Still gated on Luhn at the call site, so this alone doesn't over-trigger.
    private static final Pattern EMBEDDED_CARD_PATTERN = Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b");
    // Canonical UUID (8-4-4-4-12 hex) — extremely common as a benign correlation/trace/session ID.
    // Excluded from the high-risk-unknown heuristic below so trace IDs aren't needlessly masked.
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "credit_card", "card_number", "card_no", "cc_number", "pan", "card",
            "cvv", "cvc", "cvv2", "security_code",
            "ssn", "social_security", "social_security_number", "tax_id",
            "email", "email_address", "customer_email",
            "password", "passwd", "secret", "token", "auth_token",
            "authorization", "phone", "phone_number", "mobile"
    );

    private static final Set<String> KNOWN_SAFE_NAMES = Set.of(
            // ── Monetary / numeric business fields ──
            "amount", "currency", "status", "type", "name", "merchant",
            "description", "count", "total", "price", "quantity", "balance",
            "subtotal", "tax", "discount", "fee", "rate", "score", "weight",
            "width", "height", "length", "size", "age", "limit", "threshold",

            // ── Entity IDs ──
            "account_id", "payment_id", "order_id", "transaction_id", "merchant_id",
            "customer_id", "refund_id", "transfer_id", "shipment_id", "tracking_id",
            "product_id", "item_id", "invoice_id", "receipt_id", "ticket_id",
            "employee_id", "user_id", "session_id", "request_id", "correlation_id",
            "checkout_id", "batch_id", "job_id", "event_id", "message_id",

            // ── Timestamps / dates ──
            "created_at", "updated_at", "timestamp", "date", "time",
            "start_date", "end_date", "expiry", "exp_date", "due_date",
            "eta", "deadline", "scheduled_at", "completed_at", "cancelled_at",

            // ── Address / location (non-PII) ──
            "street", "address", "city", "state", "zip", "zip_code", "postal_code",
            "country", "region", "location", "warehouse", "district", "zone",
            "latitude", "longitude", "county",

            // ── Logistics / shipping ──
            "carrier", "shipping", "tracking", "priority", "destination",
            "origin", "route", "method", "mode", "service_level",
            "delivery_date", "ship_date", "estimated_delivery",

            // ── Product / catalog ──
            "category", "brand", "sku", "upc", "model", "color", "material",
            "department", "aisle", "shelf", "vendor", "supplier",
            "card_type", "card_brand",

            // ── Business state / classification ──
            "username", "tier", "reason", "source", "channel", "platform",
            "version", "environment", "label", "tag", "group", "role",
            "action", "operation", "event_type", "result", "outcome",
            "message", "note", "comment", "reference", "code",

            // ── Boolean-ish / flag names ──
            "enabled", "active", "verified", "approved", "processed",
            "ack", "confirmed", "completed", "visible", "available"
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

            // Recursive for arrays (payment_methods[], line_items[], cards[]...) — previously
            // ENTIRELY UNHANDLED, meaning any PCI/PII nested inside a JSON array sailed through
            // untouched. Object elements recurse like nested Maps above; scalar elements (a bare
            // list of card-number strings, say) run through the same classifyScalar() the plain
            // field path below uses — one classification path, not two to keep in sync.
            if (value instanceof List<?> list) {
                List<Object> sanitizedList = new ArrayList<>();
                for (Object element : list) {
                    if (element instanceof Map) {
                        SanitizationResult nested = sanitize((Map<String, Object>) element);
                        sanitizedList.add(nested.sanitizedBody());
                        SanitizationSummary ns = nested.summary();
                        pciMasked += ns.pciFieldsMasked();
                        piiMasked += ns.piiFieldsMasked();
                        safeKept += ns.safeFieldsKept();
                        unknown += ns.unknownFields();
                        highRisk += ns.highRiskUnknowns();
                        details.addAll(ns.fieldDetails());
                    } else {
                        ScalarOutcome outcome = classifyScalar(key, element, details);
                        sanitizedList.add(outcome.value());
                        pciMasked += outcome.pci();
                        piiMasked += outcome.pii();
                        safeKept += outcome.safe();
                        unknown += outcome.unknown();
                        highRisk += outcome.highRisk();
                    }
                }
                sanitized.put(key, sanitizedList);
                continue;
            }

            ScalarOutcome outcome = classifyScalar(key, value, details);
            sanitized.put(key, outcome.value());
            pciMasked += outcome.pci();
            piiMasked += outcome.pii();
            safeKept += outcome.safe();
            unknown += outcome.unknown();
            highRisk += outcome.highRisk();
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

    // ═══ SHARED SCALAR CLASSIFICATION — used by both the plain-field path and the array-element
    // path above, so the two never drift out of sync with each other ═══

    /** Delta to apply to the running summary counters, plus the value to store. */
    private record ScalarOutcome(Object value, int pci, int pii, int safe, int unknown, int highRisk) {}

    private ScalarOutcome classifyScalar(String key, Object value, List<FieldClassificationResult> details) {
        FieldClassificationResult result = classifyField(key, value);
        details.add(result);
        return switch (result.classification()) {
            case PCI_CARD -> new ScalarOutcome(maskCard(String.valueOf(value)), 1, 0, 0, 0, 0);
            case PCI_CVV, PCI_AUTH_TOKEN -> new ScalarOutcome("****", 1, 0, 0, 0, 0);
            case PII_SSN -> new ScalarOutcome(maskSsn(String.valueOf(value)), 0, 1, 0, 0, 0);
            case PII_EMAIL -> new ScalarOutcome("****@masked.com", 0, 1, 0, 0, 0);
            case PII_PHONE -> new ScalarOutcome("****", 0, 1, 0, 0, 0);
            case UNKNOWN -> isHighRiskUnknown(value)
                    ? new ScalarOutcome("****", 0, 0, 0, 1, 1)
                    : new ScalarOutcome(value, 0, 0, 0, 1, 0);
            default -> new ScalarOutcome(value, 0, 0, 1, 0, 0);
        };
    }

    // ═══ CLASSIFICATION — 4 layers ═══

    public FieldClassificationResult classifyField(String fieldName, Object value) {
        String lowerName = fieldName.toLowerCase();

        // LAYER 1: Field name check (cheapest)
        if (SENSITIVE_FIELD_NAMES.contains(lowerName)) {
            FieldClassification type = classifyByName(lowerName);
            return new FieldClassificationResult(fieldName, value, type, "MASKED", 1);
        }

        // KNOWN_SAFE_NAMES used to short-circuit here unconditionally. That broke this class's own
        // documented "defense-in-depth" promise (layer 3 should catch what layer 1 misses) for
        // free-text fields like "reason"/"username"/"description" — a card number or email pasted
        // into one of those was never pattern-scanned at all. Fix: numeric/boolean/null safe-named
        // fields (account_id, order_id, amount...) KEEP the full bypass below, because that's
        // precisely why they're whitelisted — they're numeric IDs that would otherwise look
        // card/SSN-shaped, and re-running pattern checks on them would trade a precision win on
        // free text for a precision loss on IDs. STRING safe-named fields fall through to Layers
        // 2-4 like anything else instead of returning early.
        boolean knownSafeName = KNOWN_SAFE_NAMES.contains(lowerName);
        if (knownSafeName && !(value instanceof String)) {
            return new FieldClassificationResult(fieldName, value, FieldClassification.SAFE_ENUM, "KEPT", 1);
        }

        if (!knownSafeName) {
            // Fuzzy name matching — GUARDED by a value-shape check. A field name merely
            // *containing* "card"/"social"/"email" is not enough evidence on its own: real traffic
            // is full of look-alikes ("giftcard_theme", "social_media_handle", "email_opt_in") whose
            // VALUES are obviously not card numbers / SSNs / email addresses. Blindly trusting the
            // substring match was a confirmed false-positive source (e.g. giftcard_theme:"Birthday"
            // was being masked as PCI_CARD). If the value doesn't plausibly match the field's implied
            // type, fall through to the generic type/pattern layers below instead of guessing.
            if ((lowerName.contains("card") || lowerName.contains("cc_")) && looksLikeCardValue(value)) {
                return new FieldClassificationResult(fieldName, value, FieldClassification.PCI_CARD, "MASKED", 1);
            }
            if ((lowerName.contains("social") || lowerName.contains("ssn")) && looksLikeSsnValue(value)) {
                return new FieldClassificationResult(fieldName, value, FieldClassification.PII_SSN, "MASKED", 1);
            }
            if (lowerName.contains("email") && value instanceof String s && s.contains("@")) {
                return new FieldClassificationResult(fieldName, value, FieldClassification.PII_EMAIL, "MASKED", 1);
            }
        }

        // LAYER 2: Value type check
        if (value == null) {
            return new FieldClassificationResult(fieldName, null, FieldClassification.SAFE_ENUM, "KEPT", 2);
        }
        if (value instanceof Boolean) {
            return new FieldClassificationResult(fieldName, value, FieldClassification.SAFE_BOOLEAN, "KEPT", 2);
        }
        if (value instanceof Number number) {
            // Previously EVERY Number was automatically safe, so a PAN or SSN arriving as a JSON
            // number under a non-whitelisted name (common — many APIs emit numeric IDs) bypassed
            // every pattern check that strings get. Known-safe-named numeric fields already exited
            // above, so by the time we get here the field name gave no safety guarantee — worth the
            // same cheap Luhn/SSN check strings already get, via the decimal-free string form.
            String digits = number.toString();
            if (digits.matches("\\d{13,19}") && luhnCheck(digits)) {
                return new FieldClassificationResult(fieldName, value, FieldClassification.PCI_CARD, "MASKED", 2);
            }
            if (digits.matches("\\d{9}") && isPlausibleSsnDigits(digits)) {
                return new FieldClassificationResult(fieldName, value, FieldClassification.PII_SSN, "MASKED", 2);
            }
            return new FieldClassificationResult(fieldName, value, FieldClassification.SAFE_NUMBER, "KEPT", 2);
        }

        // LAYER 3: Value pattern matching (regex + Luhn) — strings only
        if (value instanceof String strVal) {
            // Credit card check — fast path: the WHOLE value (after stripping separators) is
            // purely the digit string.
            String digitsOnly = strVal.replaceAll("[\\s-]", "");
            if (digitsOnly.length() >= 13 && digitsOnly.length() <= 19 &&
                    digitsOnly.matches("\\d+") && luhnCheck(digitsOnly)) {
                return new FieldClassificationResult(fieldName, value, FieldClassification.PCI_CARD, "MASKED", 3);
            }

            // Credit card check — embedded in free text, e.g. "Card number 4111 1111 1111 1111 on
            // file". The fast path above requires the ENTIRE value to be numeric, so it never fires
            // once there's a single surrounding word. This extracts any card-shaped digit run
            // (optionally grouped by single spaces/dashes) from anywhere in the string and still
            // requires it to pass Luhn, so random digit runs in prose won't trip it by accident.
            Matcher embeddedCard = EMBEDDED_CARD_PATTERN.matcher(strVal);
            while (embeddedCard.find()) {
                if (luhnCheck(embeddedCard.group().replaceAll("[\\s-]", ""))) {
                    return new FieldClassificationResult(fieldName, value, FieldClassification.PCI_CARD, "MASKED", 3);
                }
            }

            // SSN pattern (dashed: 123-45-6789)
            if (SSN_PATTERN.matcher(strVal).find()) {
                return new FieldClassificationResult(fieldName, value, FieldClassification.PII_SSN, "MASKED", 3);
            }

            // SSN pattern (bare 9 digits, e.g. under an unexpected field name like "taxpayer_id") —
            // gated on isPlausibleSsnDigits() so we don't start flagging every bare 9-digit number
            // (account numbers, zip+4-without-dash, etc.) as an SSN. Confirmed recall gap: a plain
            // undashed SSN under a non-obvious field name previously sailed through untouched.
            if (SSN_UNFORMATTED_PATTERN.matcher(strVal).matches() && isPlausibleSsnDigits(strVal)) {
                return new FieldClassificationResult(fieldName, value, FieldClassification.PII_SSN, "MASKED", 3);
            }

            // Email pattern
            if (EMAIL_PATTERN.matcher(strVal).find()) {
                return new FieldClassificationResult(fieldName, value, FieldClassification.PII_EMAIL, "MASKED", 3);
            }

            // JWT/Bearer token, API key, or a BARE JWT with no "Bearer " prefix. The bare-JWT check
            // requires all three dot-separated segments to be at least 10 chars — strong enough to
            // avoid tripping on short dotted look-alikes (version strings, domains) while catching
            // apps that store the raw token without a scheme prefix.
            if (JWT_PATTERN.matcher(strVal).find() || API_KEY_PATTERN.matcher(strVal).find()
                    || BARE_JWT_PATTERN.matcher(strVal).matches()) {
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

        // A known-safe-named String field that made it this far already survived every pattern
        // check in Layers 2-3 unmatched — the field's NAME already told us it's safe, so don't
        // let Layer 4's narrow shape heuristics (which only recognize "ALLCAPS" and "AB-123"
        // formats) misclassify an ordinary ID like "MERCH_1092" or "ORD_9981" as UNKNOWN just
        // because it uses underscores. This keeps the Bucket-5 defense-in-depth fix (string
        // safe-named fields still get pattern-scanned above) from regressing the common case.
        if (knownSafeName) {
            return new FieldClassificationResult(fieldName, value, FieldClassification.SAFE_ENUM, "KEPT", 4);
        }

        return new FieldClassificationResult(fieldName, value, FieldClassification.UNKNOWN, "UNKNOWN", 4);
    }

    // ═══ HELPERS ═══

    /**
     * Value-shape guard for the "card" fuzzy name match: does this look at all like card data
     * (purely numeric once separators are stripped), as opposed to an unrelated field that just
     * happens to have "card" in its name? Deliberately NOT length-gated to 12-19 digits — fields
     * like card_last_four:"1111" are genuinely card-related and short on purpose, and the
     * existing TaskSpecIngestionTest relies on those still being masked. The actual confirmed
     * false positive this guards against is purely NON-numeric values (giftcard_theme:"Birthday",
     * scorecard_rating:"A") being masked purely off the "card" substring with no numeric content
     * at all.
     */
    private boolean looksLikeCardValue(Object value) {
        String digits = switch (value) {
            case String s -> s.replaceAll("[\\s-]", "");
            case Number n -> n.toString();
            default -> "";
        };
        return !digits.isEmpty() && digits.matches("\\d+");
    }

    /**
     * Value-shape guard for the "social"/"ssn" fuzzy name match: does this look like a 9-digit
     * SSN (dashed or bare)? Prevents masking things like social_media_handle:"coolchef_nyc"
     * purely because the field name contains "social".
     */
    private boolean looksLikeSsnValue(Object value) {
        if (!(value instanceof String s)) return false;
        if (SSN_PATTERN.matcher(s).find()) return true;
        return SSN_UNFORMATTED_PATTERN.matcher(s).matches() && isPlausibleSsnDigits(s);
    }

    /**
     * SSA-published invalid-SSN ranges: area 000/666/900-999, group 00, or serial 0000 can never
     * be a real SSN. Rejecting those brings down the false-positive rate on bare 9-digit numbers
     * (phone-like strings, generic reference numbers) enough to make the bare-digit SSN check
     * above worth enabling without tanking precision.
     */
    private boolean isPlausibleSsnDigits(String nineDigits) {
        String area = nineDigits.substring(0, 3);
        String group = nineDigits.substring(3, 5);
        String serial = nineDigits.substring(5, 9);
        if (area.equals("000") || area.equals("666") || area.charAt(0) == '9') return false;
        if (group.equals("00")) return false;
        return !serial.equals("0000");
    }

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
        // Canonical UUIDs are an extremely common, benign correlation/trace/session ID format in
        // real enterprise traffic. Confirmed precision gap: these were being masked purely for
        // being long and hex-and-hyphen-shaped, which is indistinguishable from "random token"
        // under the heuristics below unless explicitly excluded first.
        if (UUID_PATTERN.matcher(s).matches()) return false;
        // A long digit run EMBEDDED in a mixed alphanumeric string is a weak signal of an
        // unrecognized sensitive identifier smuggled into a free-text field. A string that is
        // ENTIRELY digits is far more likely to be an ordinary numeric business identifier
        // (order number, tracking number, routing/warehouse code) — it already had its shot at
        // being caught by the explicit Luhn/SSN checks above and wasn't. Confirmed precision
        // gap: a plain 16-digit non-Luhn reference number (not a card — Luhn says so) was still
        // getting masked purely for "being 10+ chars with 6+ digits", which describes almost
        // every numeric business ID in existence.
        boolean isPureDigits = s.matches("\\d+");
        if (!isPureDigits && s.length() >= 10 && s.matches(".*\\d{6,}.*")) return true;
        // Previously this branch didn't exclude pure-digit strings the way the one above
        // deliberately does, so an ordinary 20+ digit tracking/shipment/warehouse number tripped
        // it despite the comment above explicitly saying pure-digit strings are safe business IDs.
        // Confirmed false positive, fixed by applying the same !isPureDigits guard here too.
        if (!isPureDigits && s.length() >= 20 && s.matches("[A-Za-z0-9+/=_-]+")) return true;
        return false;
    }

    private SanitizationSummary emptySummary() {
        return new SanitizationSummary(0, 0, 0, 0, 0, 0, 0, List.of());
    }
}
