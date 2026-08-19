package com.blindway.accessibility.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record VerificationRequest(@NotNull Decision decision, @Size(max = 500) String note) {

    public enum Decision {
        CONFIRM,
        REJECT
    }
}
