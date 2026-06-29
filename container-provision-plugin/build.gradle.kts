plugins {
    `java-library`
    `java-gradle-plugin`
    kotlin("jvm")
}

group = "education.cccp"
version = "0.0.1"

kotlin.jvmToolchain(21)

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(platform("education.cccp:workspace-bom:0.0.5"))
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
