package com.blindway.accessibility.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record VerificationRequest(@NotNull Decision decision, RiskLevel riskLevel, @Size(max = 500) String note) {

    @AssertTrue(message = "确认票必须选择风险等级，否决票不得选择等级")
    public boolean isRiskLevelValid() {
        return decision == null || (decision == Decision.CONFIRM) == (riskLevel != null);
    }

    public enum Decision {
        CONFIRM,
        REJECT
    }

    public enum RiskLevel {
        HIGH,
        MEDIUM,
        LOW
    }
}
