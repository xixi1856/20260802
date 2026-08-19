package com.blindway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.blindway.accessibility.api.CreateIssueRequest;
import com.blindway.accessibility.api.EvidenceRequest;
import com.blindway.accessibility.api.TransitionIssueRequest;
import com.blindway.accessibility.api.VerificationRequest;
import com.blindway.device.api.BindDeviceRequest;
import com.blindway.device.api.ProvisionDeviceRequest;
import com.blindway.identity.api.LoginRequest;
import com.blindway.identity.api.RefreshRequest;
import com.blindway.identity.api.RegisterRequest;
import com.blindway.trip.api.StartTripRequest;
import com.blindway.trip.api.TrackPointBatchRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RestContractExamplesTest {

    private static final Path EXAMPLES = Path.of("contracts", "examples", "rest");
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validExamplesDeserializeAndPassBeanValidation() throws Exception {
        for (ExampleCase example : cases()) {
            Object request = json.readValue(Files.readString(EXAMPLES.resolve(example.validFile())), example.type());
            assertThat(validator.validate(request))
                    .as("valid example %s", example.validFile())
                    .isEmpty();
        }
    }

    @Test
    void invalidExamplesFailBeanValidation() throws Exception {
        for (ExampleCase example : cases()) {
            Object request = json.readValue(Files.readString(EXAMPLES.resolve(example.invalidFile())), example.type());
            assertThat(validator.validate(request))
                    .as("invalid example %s", example.invalidFile())
                    .isNotEmpty();
        }
    }

    private List<ExampleCase> cases() {
        return List.of(
                example("register", RegisterRequest.class),
                example("login", LoginRequest.class),
                example("refresh", RefreshRequest.class),
                example("provision-device", ProvisionDeviceRequest.class),
                example("bind-device", BindDeviceRequest.class),
                example("start-trip", StartTripRequest.class),
                example("track-points", TrackPointBatchRequest.class),
                example("create-issue", CreateIssueRequest.class),
                example("verification", VerificationRequest.class),
                example("transition-issue", TransitionIssueRequest.class),
                example("evidence", EvidenceRequest.class));
    }

    private ExampleCase example(String name, Class<?> type) {
        return new ExampleCase(name + ".valid.json", name + ".invalid.json", type);
    }

    private record ExampleCase(String validFile, String invalidFile, Class<?> type) {}
}
