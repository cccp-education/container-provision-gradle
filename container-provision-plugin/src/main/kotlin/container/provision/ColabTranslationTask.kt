package container.provision

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.nio.file.Path

abstract class ColabTranslationTask : DefaultTask() {

    @get:Input
    abstract val colabNotebookUrl: Property<String>

    @get:Input
    abstract val targetLangs: Property<String>

    @get:Input
    abstract val siteRepoUrl: Property<String>

    @get:Input
    abstract val chromeUserDataDir: Property<String>

    @TaskAction
    fun provisionColab() {
        val playwright = Playwright.create()
        try {
            val launchOptions = BrowserType.LaunchOptions().setHeadless(false)
            if (chromeUserDataDir.isPresent && chromeUserDataDir.get().isNotBlank()) {
                launchOptions.args = listOf("--user-data-dir=${chromeUserDataDir.get()}")
            }
            val browser: Browser = playwright.chromium().launch(launchOptions)
            try {
                val page = browser.newPage()
                page.navigate(colabNotebookUrl.get())

                logger.lifecycle("Opened Colab: ${colabNotebookUrl.get()}")

                val connectBtn = page.waitForSelector("colab-connect-button")
                if (connectBtn != null) {
                    connectBtn.click()
                    logger.lifecycle("Runtime connecting...")
                    page.waitForTimeout(15000.0)
                } else {
                    logger.lifecycle("Runtime already connected.")
                }

                val runAll = page.locator("[label*=\"Run all\"]").first()
                if (runAll.isVisible()) {
                    runAll.click()
                } else {
                    page.getByText("Runtime").click()
                    page.getByText("Run all").click()
                }
                logger.lifecycle("Notebook execution started.")

                val deadline = System.currentTimeMillis() + 900_000
                var done = false
                while (System.currentTimeMillis() < deadline && !done) {
                    val text = page.textContent("colab-terminal, .output_text, pre") ?: ""
                    if (text.contains("TRANSLATION_DONE=ok")) {
                        done = true
                        logger.lifecycle("Translation completed.")
                    } else {
                        Thread.sleep(5000)
                    }
                }
                if (!done) {
                    throw RuntimeException("TIMEOUT: Colab did not complete within 15 minutes.")
                }
            } finally {
                browser.close()
            }
        } finally {
            playwright.close()
        }
    }
}
