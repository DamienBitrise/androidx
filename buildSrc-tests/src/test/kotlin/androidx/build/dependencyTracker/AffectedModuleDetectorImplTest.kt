/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.build.dependencyTracker

import androidx.build.gitclient.Commit
import androidx.build.gitclient.GitClient
import androidx.build.gitclient.GitCommitRange
import org.gradle.api.Project
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.testfixtures.ProjectBuilder
import org.hamcrest.CoreMatchers
import org.hamcrest.MatcherAssert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

@RunWith(JUnit4::class)
class AffectedModuleDetectorImplTest {

    @Rule
    @JvmField
    val attachLogsRule = AttachLogsTestRule()
    private val logger = attachLogsRule.logger

    @Rule
    @JvmField
    val tmpFolder = TemporaryFolder()

    @Rule
    @JvmField
    val tmpFolder2 = TemporaryFolder()

    private lateinit var root: Project
    private lateinit var p1: Project
    private lateinit var p2: Project
    private lateinit var p3: Project
    private lateinit var p4: Project
    private lateinit var p5: Project
    private lateinit var p6: Project
    private lateinit var p7: Project
    private lateinit var p8: Project
    private lateinit var p9: Project
    private lateinit var p10: Project
    private lateinit var p11: Project
    private val cobuiltTestPaths = setOf(setOf("cobuilt1", "cobuilt2"))
    private val ignoredPaths = setOf("ignored/")

    @Before
    fun init() {
        val tmpDir = tmpFolder.root

        /*

        Dummy project file tree:

               root -----------------
              / |  \     |   |   |   |
            p1  p7  p2  p8   p9 p10  p11
           /         \
          p3          p5
         /  \
       p4   p6

        Dependency forest:

            p1    p2    p7 p8  p9 p10 p11
           /  \  /  \
          p3   p5   p6
         /
        p4

         */

        root = ProjectBuilder.builder()
            .withProjectDir(tmpDir)
            .withName("root")
            .build()
        // Project Graph expects supportRootFolder.
        (root.properties.get("ext") as ExtraPropertiesExtension).set("supportRootFolder", tmpDir)
        p1 = ProjectBuilder.builder()
            .withProjectDir(tmpDir.resolve("p1"))
            .withName("p1")
            .withParent(root)
            .build()
        p2 = ProjectBuilder.builder()
            .withProjectDir(tmpDir.resolve("p2"))
            .withName("p2")
            .withParent(root)
            .build()
        p3 = ProjectBuilder.builder()
            .withProjectDir(tmpDir.resolve("p1:p3"))
            .withName("p3")
            .withParent(p1)
            .build()
        val p3config = p3.configurations.create("p3config")
        p3config.dependencies.add(p3.dependencies.project(mutableMapOf("path" to ":p1")))
        p4 = ProjectBuilder.builder()
            .withProjectDir(tmpDir.resolve("p1:p3:p4"))
            .withName("p4")
            .withParent(p3)
            .build()
        val p4config = p4.configurations.create("p4config")
        p4config.dependencies.add(p4.dependencies.project(mutableMapOf("path" to ":p1:p3")))
        p5 = ProjectBuilder.builder()
            .withProjectDir(tmpDir.resolve("p2:p5"))
            .withName("p5")
            .withParent(p2)
            .build()
        val p5config = p5.configurations.create("p5config")
        p5config.dependencies.add(p5.dependencies.project(mutableMapOf("path" to ":p2")))
        p5config.dependencies.add(p5.dependencies.project(mutableMapOf("path" to ":p1:p3")))
        p6 = ProjectBuilder.builder()
            .withProjectDir(tmpDir.resolve("p1:p3:p6"))
            .withName("p6")
            .withParent(p3)
            .build()
        val p6config = p6.configurations.create("p6config")
        p6config.dependencies.add(p6.dependencies.project(mutableMapOf("path" to ":p2")))
        p7 = ProjectBuilder.builder()
            .withProjectDir(tmpDir.resolve("p7"))
            .withName("p7")
            .withParent(root)
            .build()
        p8 = ProjectBuilder.builder()
            .withProjectDir(tmpDir.resolve("p8"))
            .withName("cobuilt1")
            .withParent(root)
            .build()
        p9 = ProjectBuilder.builder()
            .withProjectDir(tmpDir.resolve("p9"))
            .withName("cobuilt2")
            .withParent(root)
            .build()
        p10 = ProjectBuilder.builder()
            .withProjectDir(tmpDir.resolve("p10"))
            .withName("benchmark")
            .withParent(root)
            .build()
        p11 = ProjectBuilder.builder()
            .withProjectDir(tmpDir.resolve("p11"))
            .withName("placeholder-tests")
            .withParent(root)
            .build()
    }

    @Test
    fun noChangeCLs() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            cobuiltTestPaths = cobuiltTestPaths,
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = emptyList()
            )
        )
        MatcherAssert.assertThat(
            detector.changedProjects,
            CoreMatchers.`is`(
                setOf(p11)
            )
        )
        MatcherAssert.assertThat(
            detector.dependentProjects,
            CoreMatchers.`is`(
                setOf(p11)
            )
        )
        MatcherAssert.assertThat(
            detector.buildAll,
            CoreMatchers.`is`(
                true
            )
        )
    }

    @Test
    fun changeInOne() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            cobuiltTestPaths = cobuiltTestPaths,
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = listOf(convertToFilePath("p1", "foo.java"))
            )
        )
        MatcherAssert.assertThat(
            detector.changedProjects,
            CoreMatchers.`is`(
                setOf(p1, p11)
            )
        )
        MatcherAssert.assertThat(
            detector.dependentProjects,
            CoreMatchers.`is`(
                setOf(p3, p4, p5, p11)
            )
        )
    }

    @Test
    fun changeInTwo() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            cobuiltTestPaths = cobuiltTestPaths,
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = listOf(
                    convertToFilePath("p1", "foo.java"),
                    convertToFilePath("p2", "bar.java")
                )
            )
        )
        MatcherAssert.assertThat(
            detector.changedProjects,
            CoreMatchers.`is`(
                setOf(p1, p2, p11)
            )
        )
        MatcherAssert.assertThat(
            detector.dependentProjects,
            CoreMatchers.`is`(
                setOf(p3, p4, p5, p6, p11)
            )
        )
    }

    @Test
    fun changeInRoot() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            cobuiltTestPaths = cobuiltTestPaths,
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = listOf("foo.java")
            )
        )
        MatcherAssert.assertThat(
            detector.changedProjects,
            CoreMatchers.`is`(
                setOf(p11)
            )
        )
        MatcherAssert.assertThat(
            detector.dependentProjects,
            CoreMatchers.`is`(
                setOf(p11)
            )
        )
        MatcherAssert.assertThat(
            detector.buildAll,
            CoreMatchers.`is`(
                true
            )
        )
    }

    @Test
    fun changeInRootAndSubproject() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            cobuiltTestPaths = cobuiltTestPaths,
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = listOf("foo.java", convertToFilePath("p7", "bar.java"))
            )
        )
        MatcherAssert.assertThat(
            detector.changedProjects,
            CoreMatchers.`is`(
                setOf(p7, p11)
            )
        )
        MatcherAssert.assertThat(
            detector.dependentProjects,
            CoreMatchers.`is`(
                setOf(p11)
            )
        )
        MatcherAssert.assertThat(
            detector.buildAll,
            CoreMatchers.`is`(
                true
            )
        )
    }

    @Test
    fun changeInCobuilt() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            cobuiltTestPaths = cobuiltTestPaths,
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = listOf(
                    convertToFilePath(
                        "p8", "foo.java"
                    )
                )
            )
        )
        MatcherAssert.assertThat(
            detector.changedProjects,
            CoreMatchers.`is`(
                setOf(p8, p9, p11)
            )
        )
        MatcherAssert.assertThat(
            detector.dependentProjects,
            CoreMatchers.`is`(
                setOf(p11)
            )
        )
    }

    @Test(expected = IllegalStateException::class)
    fun changeInCobuiltMissingCobuilt() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            cobuiltTestPaths = setOf(setOf("cobuilt1", "cobuilt2", "cobuilt3")),
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = listOf(
                    convertToFilePath(
                        "p8", "foo.java"
                    )
                )
            )
        )
        // This should trigger IllegalStateException due to missing cobuilt3
        detector.changedProjects
    }

    @Test
    fun changeInCobuiltAllCobuiltsMissing() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            cobuiltTestPaths = setOf(setOf("cobuilt3", "cobuilt4", "cobuilt5")),
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = listOf(
                    convertToFilePath(
                        "p8", "foo.java"
                    )
                )
            )
        )
        // There should be no exception thrown here because *all* cobuilts are missing.
        detector.changedProjects
    }

    @Test
    fun projectSubset() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = listOf(convertToFilePath("p1", "foo.java"))
            )
        )
        // Verify expectations on affected projects
        MatcherAssert.assertThat(
            detector.changedProjects,
            CoreMatchers.`is`(
                setOf(p1, p11)
            )
        )
        MatcherAssert.assertThat(
            detector.dependentProjects,
            CoreMatchers.`is`(
                setOf(p3, p4, p5, p11)
            )
        )
        // Test changed
        MatcherAssert.assertThat(
            detector.getSubset(p1),
            CoreMatchers.`is`(
                ProjectSubset.CHANGED_PROJECTS
            )
        )
        // Test dependent
        MatcherAssert.assertThat(
            detector.getSubset(p3),
            CoreMatchers.`is`(
                ProjectSubset.DEPENDENT_PROJECTS
            )
        )
        // Random unrelated project should return none
        MatcherAssert.assertThat(
            detector.getSubset(p7),
            CoreMatchers.`is`(
                ProjectSubset.NONE
            )
        )
    }

    @Test
    fun projectSubset_noChangedFiles() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = emptyList()
            )
        )
        // Verify expectations on affected projects
        MatcherAssert.assertThat(
            detector.affectedProjects,
            CoreMatchers.`is`(
                setOf(p11)
            )
        )
        // No changed files in postsubmit -> return all
        MatcherAssert.assertThat(
            detector.getSubset(p1),
            CoreMatchers.`is`(
                ProjectSubset.NONE
            )
        )
        MatcherAssert.assertThat(
            detector.getSubset(p3),
            CoreMatchers.`is`(
                ProjectSubset.NONE
            )
        )
        // Only the placeholder test should return CHANGED_PROJECTS
        MatcherAssert.assertThat(
            detector.getSubset(p11),
            CoreMatchers.`is`(
                ProjectSubset.CHANGED_PROJECTS
            )
        )
        MatcherAssert.assertThat(
            detector.buildAll,
            CoreMatchers.`is`(
                true
            )
        )
    }

    @Test
    fun projectSubset_unknownChangedFiles() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = listOf(convertToFilePath("unknown", "file.java"))
            )
        )
        // Verify expectations on affected projects
        MatcherAssert.assertThat(
            detector.changedProjects,
            CoreMatchers.`is`(
                setOf(p11)
            )
        )
        MatcherAssert.assertThat(
            detector.dependentProjects,
            CoreMatchers.`is`(
                setOf(p11)
            )
        )
        // Everything should return NONE in buildall case
        MatcherAssert.assertThat(
            detector.getSubset(p1),
            CoreMatchers.`is`(
                ProjectSubset.NONE
            )
        )
        MatcherAssert.assertThat(
            detector.getSubset(p3),
            CoreMatchers.`is`(
                ProjectSubset.NONE
            )
        )
        // Only the placeholder test should return CHANGED_PROJECTS
        MatcherAssert.assertThat(
            detector.getSubset(p11),
            CoreMatchers.`is`(
                ProjectSubset.CHANGED_PROJECTS
            )
        )
    }

    @Test
    fun changeInPlaygroundCommon() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            cobuiltTestPaths = cobuiltTestPaths,
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = listOf("playground-common/tmp.kt")
            )
        )
        MatcherAssert.assertThat(
            detector.changedProjects,
            CoreMatchers.`is`(
                setOf(p11)
            )
        )
        MatcherAssert.assertThat(
            detector.buildAll,
            CoreMatchers.`is`(
                false
            )
        )
    }

    @Test
    fun changeInGithubConfig() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            cobuiltTestPaths = cobuiltTestPaths,
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = listOf(".github/workflows/bar.txt")
            )
        )
        MatcherAssert.assertThat(
            detector.changedProjects,
            CoreMatchers.`is`(
                setOf(p11)
            )
        )
        MatcherAssert.assertThat(
            detector.buildAll,
            CoreMatchers.`is`(
                false
            )
        )
    }

    @Test
    fun changeInPlaygroundCommonAndRoot() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            cobuiltTestPaths = cobuiltTestPaths,
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = listOf("playground-common/tmp.kt", "root.txt")
            )
        )
        MatcherAssert.assertThat(
            detector.buildAll,
            CoreMatchers.`is`(
                true
            )
        )
    }

    @Test
    fun `Only ignored file`() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            cobuiltTestPaths = cobuiltTestPaths,
            ignoredPaths = ignoredPaths,
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = listOf(convertToFilePath("ignored", "example.txt"))
            )
        )
        MatcherAssert.assertThat(
            detector.buildAll,
            CoreMatchers.`is`(
                false
            )
        )
    }

    @Test
    fun `Ignored file and changed file`() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            cobuiltTestPaths = cobuiltTestPaths,
            ignoredPaths = ignoredPaths,
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = listOf(
                    convertToFilePath("ignored", "example.txt"),
                    convertToFilePath("p1", "foo.kt")
                )
            )
        )
        MatcherAssert.assertThat(
            detector.changedProjects,
            CoreMatchers.`is`(
                setOf(p1, p11)
            )
        )
        MatcherAssert.assertThat(
            detector.buildAll,
            CoreMatchers.`is`(
                false
            )
        )
    }

    @Test
    fun `Ignored file and unknown file`() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            cobuiltTestPaths = cobuiltTestPaths,
            ignoredPaths = ignoredPaths,
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = listOf(
                    convertToFilePath("ignored", "example.txt"),
                    convertToFilePath("unknown", "foo.kt")
                )
            )
        )
        MatcherAssert.assertThat(
            detector.buildAll,
            CoreMatchers.`is`(
                true
            )
        )
    }

    @Test
    fun `Ignored file, unknown file, and changed file`() {
        val detector = AffectedModuleDetectorImpl(
            rootProject = root,
            logger = logger,
            ignoreUnknownProjects = false,
            cobuiltTestPaths = cobuiltTestPaths,
            ignoredPaths = ignoredPaths,
            injectedGitClient = MockGitClient(
                lastMergeSha = "foo",
                changedFiles = listOf(
                    "ignored/eg.txt",
                    convertToFilePath("unknown", "foo.kt"),
                    convertToFilePath("p1", "bar.kt")
                )
            )
        )
        MatcherAssert.assertThat(
            detector.buildAll,
            CoreMatchers.`is`(
                true
            )
        )
    }

    // For both Linux/Windows
    fun convertToFilePath(vararg list: String): String {
        return list.toList().joinToString(File.separator)
    }

    private class MockGitClient(
        val lastMergeSha: String?,
        val changedFiles: List<String>
    ) : GitClient {
        override fun findChangedFilesSince(
            sha: String,
            top: String,
            includeUncommitted: Boolean
        ) = changedFiles

        override fun findPreviousSubmittedChange() = lastMergeSha

        // Implement unused abstract method
        override fun getGitLog(
            gitCommitRange: GitCommitRange,
            keepMerges: Boolean,
            fullProjectDir: File
        ): List<Commit> = listOf()
    }
}
