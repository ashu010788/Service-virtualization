package com.virtualization.servicevirtualizationplatform.model;

import java.util.List;

public record SanitizationSummary(
        int totalFieldsProcessed,
        int classifiedFields,
        int pciFieldsMasked,
        int piiFieldsMasked,
        int safeFieldsKept,
        int unknownFields,
        int highRiskUnknowns,
        List<FieldClassificationResult> fieldDetails
) {}
