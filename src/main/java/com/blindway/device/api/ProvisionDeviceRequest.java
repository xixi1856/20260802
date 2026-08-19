package com.blindway.device.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProvisionDeviceRequest(@NotBlank @Size(max = 80) String label) {}
