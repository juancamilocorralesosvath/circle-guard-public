plugins {
    id("org.springframework.boot") version "3.2.4" apply false
    id("io.spring.dependency-management") version "1.1.4" apply false
    kotlin("jvm") version "1.9.24" apply false
    kotlin("plugin.spring") version "1.9.24" apply false
    kotlin("plugin.jpa") version "1.9.24" apply false
    id("org.sonarqube") version "4.4.1.3373"
    id("jacoco")
}

allprojects {
    group = "com.circleguard"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "jacoco")

    val integrationTestIncludes = listOf(
        "**/*IntegrationTest.class",
        "**/*IntegrationTests.class",
        "**/*IT.class",
        "**/*E2ETest.class",
        "**/*PerformanceTest.class",
        "**/AttachmentControllerTest.class",
        "**/AdministrativeCorrectionTest.class",
        "**/HealthStatusReevaluationTest.class"
    )
    val coverageMinimum = (findProperty("coverageMin") as String?)?.toBigDecimal() ?: "0.85".toBigDecimal()

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoPluginExtension> {
        toolVersion = "0.8.12"
    }

    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:3.2.4"))
        "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:3.2.4"))
        "compileOnly"("org.projectlombok:lombok")
        "annotationProcessor"("org.projectlombok:lombok")
        "testCompileOnly"("org.projectlombok:lombok")
        "testAnnotationProcessor"("org.projectlombok:lombok")
        "implementation"("org.jetbrains.kotlin:kotlin-reflect")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("com.h2database:h2")
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            freeCompilerArgs = listOf("-Xjsr305=strict")
            jvmTarget = "21"
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    extensions.findByType<SourceSetContainer>()?.let { sourceSets ->
        val testTask = tasks.named<Test>("test")

        testTask {
            exclude(integrationTestIncludes)
        }

        tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
            dependsOn(testTask)
            reports {
                xml.required.set(true)
                html.required.set(true)
                csv.required.set(false)
            }
        }

        tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            dependsOn(testTask)
            violationRules {
                rule {
                    limit {
                        minimum = coverageMinimum
                    }
                }
            }
        }

        tasks.named("check") {
            dependsOn(tasks.named("jacocoTestCoverageVerification"))
        }

        tasks.register<Test>("integrationTest") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Runs integration, performance, E2E, and Testcontainers-backed tests."

            val testSourceSet = sourceSets["test"]
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath

            shouldRunAfter(tasks.named("test"))
            useJUnitPlatform()
            include(integrationTestIncludes)
        }
    }
}

sonarqube {
    properties {
        property("sonar.projectKey", "circleguard")
        property("sonar.projectName", "CircleGuard")
        property("sonar.sourceEncoding", "UTF-8")
    }
}
