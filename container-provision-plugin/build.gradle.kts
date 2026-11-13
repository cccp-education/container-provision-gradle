plugins {
    `java-library`
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "2.4.10"
}

group = "education.cccp"
version = "0.0.1"

kotlin.jvmToolchain(21)

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation(platform("com.github.cccp-education.workspace-bom:workspace-bom:0.0.136"))
    implementation("com.microsoft.playwright:playwright")
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
