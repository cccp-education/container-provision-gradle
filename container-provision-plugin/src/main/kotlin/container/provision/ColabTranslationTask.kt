package container.provision

import com.microsoft.playwright.Playwright
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
        private const val CDP_PORT = 9222
        private const val POLL_INTERVAL_MS = 30_000L
        private const val COMPLETION_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes

        private fun resolveChromium(): String? {
            val snap = "/snap/chromium/current/usr/lib/chromium-browser/chrome"
            if (File(snap).isFile && File(snap).canExecute()) return snap
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

        private fun isCdpReady(port: Int): Boolean = try {
            val conn = URL("http://localhost:$port/json/version").openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.responseCode == 200
        } catch (e: Exception) {
            false
        }

        private fun gitRemoteHeadSha(repoUrl: String): String? = try {
            val r = ProcessBuilder("git", "ls-remote", repoUrl, "refs/heads/main")
                .redirectErrorStream(true).start()
            val out = r.inputStream.bufferedReader().readText().trim()
            r.waitFor()
            if (out.isEmpty()) null else out.split("\\s+".toRegex()).first()
        } catch (e: Exception) {
            null
        }
    }

    @TaskAction
    fun provisionColab() {
        val chromeBin = resolveChromium()
            ?: throw RuntimeException("Chromium binary not found")
        logger.lifecycle("Chromium: $chromeBin")

        val snapDir = "/snap/chromium/current/usr/lib/chromium-browser"
        val userDataDir = chromeUserDataDir.orNull

        val launchArgs = mutableListOf(
            chromeBin,
            "--remote-debugging-port=$CDP_PORT",
            "--remote-debugging-address=127.0.0.1",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-blink-features=AutomationControlled",
            colabNotebookUrl.get()
        )
        if (!userDataDir.isNullOrBlank()) {
            val profileDir = File(userDataDir)
            launchArgs.add("--user-data-dir=${profileDir.parentFile.absolutePath}")
            launchArgs.add("--profile-directory=${profileDir.name}")
        } else {
            launchArgs.add("--user-data-dir=${System.getProperty("java.io.tmpdir")}/cpr-chrome-profile")
        }

        logger.lifecycle("Launching Chromium: ${launchArgs.joinToString(" ")}")
        val pb = ProcessBuilder(launchArgs)
        pb.directory(File(snapDir))
        pb.redirectErrorStream(true)
        val chromeProcess = pb.start()

        try {
            // Wait for CDP endpoint
            var cdpReady = false
            for (i in 0..30) {
                if (isCdpReady(CDP_PORT)) {
                    cdpReady = true
                    break
                }
                Thread.sleep(1000)
            }
            if (!cdpReady) {
                val chromeOut = chromeProcess.inputStream.bufferedReader().readText()
                throw RuntimeException("Chromium CDP not ready on port $CDP_PORT after 30s. Output:\n$chromeOut")
            }
            logger.lifecycle("CDP ready on port $CDP_PORT")

            val playwright = Playwright.create()
            try {
                val browser = playwright.chromium().connectOverCDP("http://localhost:$CDP_PORT")
                try {
                    val context = browser.contexts().first()
                    val page = context.pages().firstOrNull() ?: context.newPage()
                    logger.lifecycle("Connected to Chromium via CDP, page url: ${page.url()}")

                    if (!page.url().contains("colab.research.google.com")) {
                        page.navigate(colabNotebookUrl.get())
                        logger.lifecycle("Navigated to Colab: ${colabNotebookUrl.get()}")
                    }

                    page.waitForLoadState()
                    Thread.sleep(5000)

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

                    logger.lifecycle("Waiting for Colab runtime to be ready...")
                    page.waitForTimeout(60000.0)

                    logger.lifecycle("Triggering Run all via keyboard shortcut Ctrl+F9...")
                    var runAllClicked = false
                    try {
                        page.keyboard().press("Control+F9")
                        runAllClicked = true
                        logger.lifecycle("Ctrl+F9 sent.")
                    } catch (e: Exception) {
                        logger.lifecycle("Keyboard shortcut failed: ${e.message}")
                    }

                    if (!runAllClicked) {
                        val strategies = listOf<Pair<String, () -> Unit>>(
                            "colab-runall-button" to {
                                val btn = page.querySelector("colab-runall-button")
                                if (btn != null && btn.isVisible()) {
                                    btn.click()
                                    runAllClicked = true
                                }
                            },
                            "[label*=\"Run all\"]" to {
                                val loc = page.locator("[label*=\"Run all\"]").first()
                                if (loc.isVisible()) {
                                    loc.click()
                                    runAllClicked = true
                                }
                            },
                            "Runtime menu → Run all" to {
                                page.getByText("Runtime").first().click()
                                Thread.sleep(1000)
                                val ra = page.getByText("Run all").first()
                                ra.waitFor()
                                ra.click()
                                runAllClicked = true
                            }
                        )
                        for ((name, action) in strategies) {
                            try {
                                logger.lifecycle("  Trying strategy: $name")
                                action()
                                if (runAllClicked) {
                                    logger.lifecycle("Notebook execution started via: $name")
                                    break
                                }
                            } catch (e: Exception) {
                                logger.lifecycle("  Strategy $name failed: ${e.message}")
                            }
                        }
                    }
                    if (!runAllClicked) {
                        throw RuntimeException("Could not click Run all — no strategy worked")
                    }
                    logger.lifecycle("Notebook execution started. Polling git for completion...")

                    val repoUrl = siteRepoUrl.orNull
                    if (repoUrl.isNullOrBlank()) {
                        logger.lifecycle("No siteRepoUrl configured — falling back to DOM polling (fragile)")
                        pollDomForCompletion(page)
                    } else {
                        pollGitForCompletion(repoUrl)
                    }
                } finally {
                    browser.close()
                }
            } finally {
                playwright.close()
            }
        } finally {
            chromeProcess.destroy()
        }
    }

    private fun pollGitForCompletion(repoUrl: String) {
        val initialSha = gitRemoteHeadSha(repoUrl)
        logger.lifecycle("Initial HEAD sha: $initialSha")
        val deadline = System.currentTimeMillis() + COMPLETION_TIMEOUT_MS
        var elapsed = 0
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            elapsed += POLL_INTERVAL_MS.toInt() / 1000
            val currentSha = gitRemoteHeadSha(repoUrl)
            if (currentSha != null && currentSha != initialSha) {
                logger.lifecycle("[$elapsed s] Git HEAD changed: $initialSha → $currentSha")
                logger.lifecycle("Translation committed to $repoUrl")
                logger.lifecycle("Pull in office/ to verify content-i18n/: git pull && ls office/sites/cheroliv.com/content-i18n/")
                return
            }
            logger.lifecycle("[$elapsed s] Waiting for Colab to push (HEAD still $currentSha)...")
        }
        throw RuntimeException("TIMEOUT: No git commit detected after ${COMPLETION_TIMEOUT_MS / 60000} minutes")
    }

    private fun pollDomForCompletion(page: com.microsoft.playwright.Page) {
        val deadline = System.currentTimeMillis() + COMPLETION_TIMEOUT_MS
        var done = false
        while (System.currentTimeMillis() < deadline && !done) {
            try {
                val text = page.textContent("colab-terminal, .output_text, pre") ?: ""
                if (text.contains("TRANSLATION_DONE=ok")) {
                    done = true
                    logger.lifecycle("Translation completed (DOM signal).")
                }
            } catch (e: Exception) {
                logger.lifecycle("Polling page error (recovering): ${e.message}")
            }
            if (!done) Thread.sleep(5000)
        }
        if (!done) {
            throw RuntimeException("TIMEOUT: Colab did not complete within ${COMPLETION_TIMEOUT_MS / 60000} minutes.")
        }
    }
}