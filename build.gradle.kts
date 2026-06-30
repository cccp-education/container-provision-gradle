plugins {
    id("education.cccp.build.cucumber") version "0.0.1"
    id("education.cccp.container-provision") version "0.0.1"
}

repositories {
    mavenLocal()
    mavenCentral()
}

containerProvision {
    colabNotebookUrl = "https://colab.research.google.com/github/cccp-education/container-provision-gradle/blob/main/colab/start-ollama-tunnel.ipynb"
    chromeUserDataDir = "/home/cheroliv/snap/chromium/common/chromium"
    targetLangs = "en"
    siteRepoUrl = "https://github.com/cheroliv/office.git"
}
