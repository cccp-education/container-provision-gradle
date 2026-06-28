plugins {
    `java-library`
    `java-gradle-plugin`
    kotlin("jvm")
}

group = "education.cccp"
version = "0.0.1"

kotlin.jvmToolchain(21)

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.microsoft.playwright:playwright:1.51.0")
}

gradlePlugin {
    plugins {
        create("containerProvision") {
            id = "education.cccp.container-provision"
            implementationClass = "container.provision.ContainerProvisionPlugin"
            displayName = "Container Provision Plugin"
            description = "Provisions Colab Ollama runtimes for cross-borough LLM tasks via Playwright"
        }
    }
}
