package qaengine.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Proves the test toolchain works on the Java 25 baseline (Phase 1 acceptance). */
class ToolchainCompatibilityTest {

    @Test
    void runsOnJava25() {
        assertTrue(Runtime.version().feature() >= 25);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mockitoWorksOnJava25() {
        Supplier<String> s = mock(Supplier.class);
        when(s.get()).thenReturn("ok");
        assertEquals("ok", s.get());
    }

    @Test
    void coreHasNoFrameworkOrBrowserDependency() {
        var classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("qaengine.core");
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "org.openqa..", "com.microsoft.playwright..")
                .allowEmptyShould(true)
                .check(classes);
    }
}
