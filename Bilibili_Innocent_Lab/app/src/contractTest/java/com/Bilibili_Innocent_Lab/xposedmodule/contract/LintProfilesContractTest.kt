package com.Bilibili_Innocent_Lab.xposedmodule.contract

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LintProfilesContractTest {
    private val repo = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "Bilibili_Innocent_Lab/app/build.gradle.kts").isFile }
    private val buildScript = SourceContract.normalize(
        File(repo, "Bilibili_Innocent_Lab/app/build.gradle.kts").readText()
    )

    @Test fun fastLintOnlyExcludesExplicitApiReplacementSuggestions() {
        val suggestions = buildScript.after("val fastLintSuggestionIds = setOf(")
            .before("\n)")
        val ids = Regex("\"([^\"]+)\"").findAll(suggestions)
            .map { it.groupValues[1] }.toList()
        assertEquals(32, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("ReplaceWith") })
        assertTrue("ReplaceWithLifecycleOwnerExtension" in ids)
        assertFalse(ids.any { it.contains("Hikage") })
    }

    @Test fun fullLintRemainsDefaultAndFastReportsAreSeparate() {
        val lint = buildScript.after("    lint {").before("\n    sourceSets {")
        assertTrue(buildScript.contains("val fastLintRequested = requestedTaskNames.contains(\"lintFast\")"))
        assertFalse(lint.before("if (fastLintRequested) {").contains("disable"))
        assertTrue(lint.contains("disable.addAll(fastLintSuggestionIds)"))
        assertTrue(buildScript.contains("val lintProfile = if (fastLintRequested) \"fast\" else \"full\""))
        assertTrue(buildScript.contains("reports/lint/\$lintProfile"))
        assertTrue(buildScript.contains("SingleArtifact.LINT_HTML_REPORT"))
        assertTrue(buildScript.contains("SingleArtifact.LINT_XML_REPORT"))
        assertTrue(buildScript.contains("\"exportDebugFastLintReports\" else \"exportDebugFullLintReports\""))
        assertTrue(lint.contains("baseline = file(\"lint-baseline.xml\")"))
        assertTrue(lint.contains("ignoreTestSources = true"))
        assertTrue(lint.contains("checkDependencies = false"))
        assertFalse(lint.contains("checkOnly"))
        assertFalse(lint.contains("abortOnError = false"))
    }

    @Test fun fastAndFullGatesCannotBeMixed() {
        assertTrue(buildScript.contains("if (fastLintRequested && fullVerificationRequested)"))
        assertTrue(buildScript.contains("Run lintFast separately from full lint/check/build gates."))
        assertTrue(buildScript.contains("dependsOn(\"lintDebug\")"))
        assertTrue(buildScript.contains("requestedTaskNames.any { it !in fastLintCompanionTasks }"))
        assertTrue(buildScript.contains("Request lintFast by its full task name"))
    }

    @Test fun allReleaseWorkflowsKeepFullLintInOneCompleteGate() {
        val helper = SourceContract.normalize(
            File(repo, ".github/release-templates/run_gradle_gates.py").readText()
        )
        for (task in listOf("assembleDebug", "testDebugUnitTest", "lintDebug", "minifyReleaseWithR8")) {
            assertTrue(task, helper.contains("\"$task\""))
        }
        assertTrue(helper.contains("\"--continue\""))
        assertTrue(helper.contains("\"--no-daemon\""))
        for (name in listOf("canary-build.yml", "alpha-release.yml", "stable-release.yml")) {
            val workflow = SourceContract.normalize(File(repo, ".github/workflows/$name").readText())
            val verification = workflow.after("      - name: Run complete Gradle verification\n")
                .before("      - name: Upload test and lint reports")
            assertTrue(name, verification.contains("run_gradle_gates.py"))
            assertTrue(name, verification.contains("--compile-sdk"))
            assertTrue(name, verification.contains("--jvmargs="))
            assertFalse(name, workflow.contains("testLayer="))
            assertFalse(name, workflow.contains("lintFast"))
            assertEquals(name, 2, Regex("uses: ./\\.github/actions/gradle-cache").findAll(workflow).count())
            val publish = workflow.after("\n  publish:")
            assertFalse(name, publish.contains("actions/cache/save@"))
            assertTrue(name, workflow.contains("Collect complete verification evidence"))
            assertTrue(name, workflow.contains("if-no-files-found: error"))
        }
        val cache = File(repo, ".github/actions/gradle-cache/action.yml").readText()
        assertTrue(cache.contains("cache-provider: external"))
        assertTrue(cache.contains("actions/cache/restore@"))
        assertTrue(cache.contains("restore-keys:"))
        assertTrue(buildScript.contains(".github/actions/**/action.yml"))
    }
}
