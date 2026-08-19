package com.blindway.media.api;

import java.util.UUID;

public record MediaResponse(UUID id, String contentType, long sizeBytes, String accessUrl) {}
