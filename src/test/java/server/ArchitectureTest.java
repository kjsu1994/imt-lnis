package server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** 계산·공통 코드가 웹 요청 및 저장 계층에 의존하지 않도록 한다. */
@AnalyzeClasses(packages = "server", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule codecs_are_independent =
            noClasses()
                    .that()
                    .resideInAnyPackage("server.afs..", "server.pvt..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework.web..",
                            "org.springframework.data..",
                            "jakarta.persistence..");
}
