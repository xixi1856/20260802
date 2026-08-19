package com.blindway.accessibility.domain;

import java.util.EnumSet;
import java.util.Set;

public enum IssueStatus {
    PENDING,
    VERIFIED,
    PROCESSING,
    REJECTED,
    RESOLVED,
    CLOSED,
    REOPENED;

    public boolean canTransitionTo(IssueStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<IssueStatus> allowedTargets() {
        return switch (this) {
            case PENDING -> EnumSet.of(VERIFIED, REJECTED);
            case VERIFIED, REOPENED -> EnumSet.of(PROCESSING, REJECTED);
            case PROCESSING -> EnumSet.of(RESOLVED);
            case RESOLVED -> EnumSet.of(CLOSED, REOPENED);
            case CLOSED -> EnumSet.of(REOPENED);
            case REJECTED -> EnumSet.of(REOPENED);
        };
    }
}
