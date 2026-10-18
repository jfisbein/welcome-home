import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("net.ltgt.errorprone") version "5.1.1"
}

group = "welcomehome"
version = "1.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "2.1.1"

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-webauthn")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("com.google.crypto.tink:tink:1.23.0")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.21.0")
    implementation("com.github.ben-manes.caffeine:caffeine")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    // Passkey authenticator emulator; same version as the webauthn4j-core used by Spring Security.
    testImplementation("com.webauthn4j:webauthn4j-test:0.31.9.RELEASE")
    // Nuki Web API stand-in; the standalone jar shades its Jetty, so it does not clash with Spring's.
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    errorprone("com.google.errorprone:error_prone_core:2.50.0")
    errorprone("com.uber.nullaway:nullaway:0.14.2")
}

// Error Prone's default checks, plus nullness at compile time: in the app's code nothing is null unless it is
// @Nullable, and NullAway fails the build where a possibly null value could be dereferenced or passed on.
// Every package is also @NullMarked, for the IDE; NullAway checks them all even if a new one is not.
// Tests are not checked for nullness: they pass nulls on purpose.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        // Our javadoc may consist of only a @return, which this style check rejects.
        disable("MissingSummary")
        check("NullAway", if (name == "compileJava") CheckSeverity.ERROR else CheckSeverity.OFF)
        option("NullAway:AnnotatedPackages", "welcomehome")
        option("NullAway:JSpecifyMode", "true")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Public API must be documented: missing or broken javadoc fails `check`.
tasks.javadoc {
    val javadocOptions = options as StandardJavadocDocletOptions
    javadocOptions.addBooleanOption("Xdoclint:all", true)
    javadocOptions.addBooleanOption("Werror", true)
}

tasks.check {
    dependsOn(tasks.javadoc)
}

tasks.bootRun {
    systemProperty("spring.profiles.active", "dev")
}

tasks.register<JavaExec>("generateKeysets") {
    group = "application"
    description = "Generates the AEAD and MAC keysets into secrets/. Never overwrites existing files."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "welcomehome.crypto.KeysetGenerator"
    args("secrets")
}

tasks.register<JavaExec>("rotateAeadKeyset") {
    group = "application"
    description = "Adds a new primary key to an AEAD keyset: ./gradlew rotateAeadKeyset --args=<keyset file>"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "welcomehome.crypto.KeysetRotator"
}
