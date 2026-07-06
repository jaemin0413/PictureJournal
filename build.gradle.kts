plugins {
    java
    id("org.springframework.boot") version "3.3.2"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.picturejournal"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val testSourceSet = sourceSets.named("test").get()

val generatedOpenApiArtifact = layout.buildDirectory.file("test-artifacts/g001-openapi-docs.json")
val checkedInOpenApiContract = layout.projectDirectory.file("contracts/openapi/picture-journal.openapi.json")

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.drewnoakes:metadata-extractor:2.19.0")
    runtimeOnly("org.postgresql:postgresql")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.register<Test>("generateOpenApiContract") {
    group = "verification"
    description = "Generates the MockMvc OpenAPI artifact without depending on unrelated runtime checks."
    useJUnitPlatform()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    include("**/OpenApiContractArtifactTests.class")
    outputs.file(generatedOpenApiArtifact)
}

tasks.register("refreshOpenApiContract") {
    group = "verification"
    description = "Refreshes the checked-in OpenAPI contract from the MockMvc-generated artifact."
    dependsOn("generateOpenApiContract")
    inputs.file(generatedOpenApiArtifact)
    outputs.file(checkedInOpenApiContract)

    doLast {
        val source = generatedOpenApiArtifact.get().asFile
        val target = checkedInOpenApiContract.asFile
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
    }
}