package com.virtualization.servicevirtualizationplatform.recording;

import com.virtualization.servicevirtualizationplatform.model.FieldClassification;
import com.virtualization.servicevirtualizationplatform.model.SanitizationSummary;
import org.springframework.stereotype.Service;

@Service
public class ConfidenceCalculator {

    public double calculate(SanitizationSummary requestSummary, SanitizationSummary responseSummary) {
        int totalFields = requestSummary.totalFieldsProcessed() + responseSummary.totalFieldsProcessed();
        int classifiedFields = requestSummary.classifiedFields() + responseSummary.classifiedFields();
        int highRiskUnknowns = requestSummary.highRiskUnknowns() + responseSummary.highRiskUnknowns();

        if (totalFields == 0) return 1.0;

        double baseConfidence = (double) classifiedFields / totalFields;
        double penalty = highRiskUnknowns * 0.05;

        return Math.max(0.0, Math.min(1.0, baseConfidence - penalty));
    }
}
