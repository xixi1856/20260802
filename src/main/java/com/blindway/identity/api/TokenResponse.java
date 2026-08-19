package com.blindway.identity.api;

public record TokenResponse(String accessToken, long expiresInSeconds, String refreshToken) {}
