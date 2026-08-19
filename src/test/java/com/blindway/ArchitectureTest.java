package com.blindway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

@AnalyzeClasses(packages = "com.blindway")
class ArchitectureTest {

    @ArchTest
    static final ArchRule CONTROLLERS_DO_NOT_ACCESS_INFRASTRUCTURE = noClasses()
            .that()
            .resideInAPackage("..api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure..");

    @Test
    void modulithBoundariesAreValid() {
        ApplicationModules.of(BlindWayApplication.class).verify();
    }
}
