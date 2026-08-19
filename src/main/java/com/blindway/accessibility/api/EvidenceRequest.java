package com.blindway.accessibility.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record EvidenceRequest(@NotNull UUID mediaAssetId) {}
