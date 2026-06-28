package container.provision

import org.gradle.api.Plugin
import org.gradle.api.Project

class ContainerProvisionPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("containerProvision", ContainerProvisionExtension::class.java)

        project.tasks.register("provisionColabTranslation", ColabTranslationTask::class.java) { task ->
            task.group = "provision"
            task.description = "Opens Colab via Playwright, runs bakery translation with Ollama on GPU"
            task.colabNotebookUrl.set(project.findProperty("cpr.colabNotebookUrl")?.toString() ?: ext.colabNotebookUrl)
            task.targetLangs.set(project.findProperty("cpr.targetLangs")?.toString() ?: ext.targetLangs)
            task.siteRepoUrl.set(project.findProperty("cpr.siteRepoUrl")?.toString() ?: ext.siteRepoUrl)
            task.chromeUserDataDir.set(
                project.findProperty("cpr.chromeUserDataDir")?.toString()
                    ?: ext.chromeUserDataDir
            )
        }
    }
}
