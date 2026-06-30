package container.provision

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.io.File
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

    companion object {
        private fun resolvePlaywrightChromium(): String? {
            val cache = System.getProperty("user.home") + "/.cache/ms-playwright"
            val candidates = listOf("chromium-1228", "chromium-1169", "chromium-1161")
            for (name in candidates) {
                val chrome64 = File(cache, "$name/chrome-linux64/chrome")
                if (chrome64.isFile && chrome64.canExecute()) return chrome64.absolutePath
                val chrome32 = File(cache, "$name/chrome-linux/chrome")
                if (chrome32.isFile && chrome32.canExecute()) return chrome32.absolutePath
            }
            return null
        }
    }

    @TaskAction
    fun provisionColab() {
        val playwright = Playwright.create()
        try {
            val userDataDir = chromeUserDataDir.orNull
            val context = if (!userDataDir.isNullOrBlank()) {
                val opts = BrowserType.LaunchPersistentContextOptions().setHeadless(false)
                resolvePlaywrightChromium()?.let { opts.setExecutablePath(Path.of(it)) }
                playwright.chromium().launchPersistentContext(Path.of(userDataDir), opts)
            } else {
                val opts = BrowserType.LaunchOptions().setHeadless(false)
                resolvePlaywrightChromium()?.let { opts.setExecutablePath(Path.of(it)) }
                playwright.chromium().launch(opts).newContext()
            }
            try {
                val page = if (context.pages().isNotEmpty()) context.pages().first() else context.newPage()
                page.navigate(colabNotebookUrl.get())
                logger.lifecycle("Opened Colab: ${colabNotebookUrl.get()}")

                page.waitForLoadState()
                Thread.sleep(3000)

                val connectBtn = page.querySelector("colab-connect-button")
                if (connectBtn != null && connectBtn.isVisible()) {
                    try {
                        connectBtn.click()
                        logger.lifecycle("Runtime connecting...")
                    } catch (e: Exception) {
                        logger.lifecycle("Connect click skipped: ${e.message}")
                    }
                } else {
                    logger.lifecycle("No connect button — runtime already connected or login required.")
                }

                page.waitForTimeout(30000.0)

                try {
                    val runAll = page.locator("[label*=\"Run all\"]").first()
                    if (runAll.isVisible()) {
                        runAll.click()
                    } else {
                        page.getByText("Runtime").first().click()
                        page.getByText("Run all").first().click()
                    }
                    logger.lifecycle("Notebook execution started.")
                } catch (e: Exception) {
                    logger.lifecycle("Run all failed (page may have changed): ${e.message}")
                    throw e
                }

                val deadline = System.currentTimeMillis() + 900_000
                var done = false
                while (System.currentTimeMillis() < deadline && !done) {
                    try {
                        val text = page.textContent("colab-terminal, .output_text, pre") ?: ""
                        if (text.contains("TRANSLATION_DONE=ok")) {
                            done = true
                            logger.lifecycle("Translation completed.")
                        }
                    } catch (e: Exception) {
                        logger.lifecycle("Polling page error (recovering): ${e.message}")
                    }
                    if (!done) Thread.sleep(5000)
                }
                if (!done) {
                    throw RuntimeException("TIMEOUT: Colab did not complete within 15 minutes.")
                }
            } finally {
                context.close()
            }
        } finally {
            playwright.close()
        }
    }
}
