package com.blindway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.blindway.accessibility.api.AccessibilityController;
import com.blindway.device.api.DeviceController;
import com.blindway.device.api.EmqxAuthController;
import com.blindway.identity.api.AuthController;
import com.blindway.map.api.MapController;
import com.blindway.media.api.MediaController;
import com.blindway.perception.api.MqttInboxAdminController;
import com.blindway.trip.api.TripController;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class OpenApiControllerConsistencyTest {

    private static final Pattern PATH = Pattern.compile("^  (/[^:]+):$");
    private static final Pattern OPERATION = Pattern.compile("^    (get|post|put|patch|delete):$");
    private static final List<Class<?>> CONTROLLERS = List.of(
            AuthController.class,
            DeviceController.class,
            TripController.class,
            AccessibilityController.class,
            MediaController.class,
            MapController.class,
            MqttInboxAdminController.class,
            EmqxAuthController.class);

    @Test
    void everyControllerOperationIsDeclaredExactlyOnceInOpenApi() throws Exception {
        assertThat(openApiOperations()).containsExactlyInAnyOrderElementsOf(controllerOperations());
    }

    private Set<String> openApiOperations() throws Exception {
        Set<String> operations = new LinkedHashSet<>();
        String currentPath = null;
        boolean readingPaths = false;
        for (String line : Files.readAllLines(Path.of("contracts", "openapi.yaml"))) {
            if (line.equals("paths:")) {
                readingPaths = true;
                continue;
            }
            if (line.equals("components:")) {
                break;
            }
            if (!readingPaths) {
                continue;
            }
            Matcher path = PATH.matcher(line);
            if (path.matches()) {
                currentPath = path.group(1);
                continue;
            }
            Matcher operation = OPERATION.matcher(line);
            if (operation.matches() && currentPath != null) {
                operations.add(operation.group(1).toUpperCase() + " " + currentPath);
            }
        }
        return operations;
    }

    private Set<String> controllerOperations() {
        Set<String> operations = new LinkedHashSet<>();
        for (Class<?> controller : CONTROLLERS) {
            String base =
                    controller.getAnnotation(RequestMapping.class).value()[0].replaceFirst("^/api/v1", "");
            for (Method method : controller.getDeclaredMethods()) {
                add(operations, "GET", base, values(method.getAnnotation(GetMapping.class)));
                add(operations, "POST", base, values(method.getAnnotation(PostMapping.class)));
                add(operations, "PUT", base, values(method.getAnnotation(PutMapping.class)));
                add(operations, "PATCH", base, values(method.getAnnotation(PatchMapping.class)));
                add(operations, "DELETE", base, values(method.getAnnotation(DeleteMapping.class)));
            }
        }
        return operations;
    }

    private void add(Set<String> operations, String verb, String base, String[] paths) {
        for (String path : paths) {
            operations.add(verb + " " + base + path);
        }
    }

    private String[] values(GetMapping mapping) {
        return mapping == null ? new String[0] : emptyAsRoot(mapping.value());
    }

    private String[] values(PostMapping mapping) {
        return mapping == null ? new String[0] : emptyAsRoot(mapping.value());
    }

    private String[] values(PutMapping mapping) {
        return mapping == null ? new String[0] : emptyAsRoot(mapping.value());
    }

    private String[] values(PatchMapping mapping) {
        return mapping == null ? new String[0] : emptyAsRoot(mapping.value());
    }

    private String[] values(DeleteMapping mapping) {
        return mapping == null ? new String[0] : emptyAsRoot(mapping.value());
    }

    private String[] emptyAsRoot(String[] values) {
        return values.length == 0 ? new String[] {""} : Arrays.copyOf(values, values.length);
    }
}
