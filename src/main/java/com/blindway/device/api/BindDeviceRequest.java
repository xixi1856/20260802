package com.blindway.device.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BindDeviceRequest(@NotBlank @Size(min = 20, max = 200) String deviceSecret) {}
