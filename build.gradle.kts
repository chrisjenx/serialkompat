import com.diffplug.gradle.spotless.SpotlessExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.bcv)
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
}

allprojects {
    group = "com.chrisjenx"
    // Version comes from `version=` in gradle.properties, overridable via `-Pversion=<x>` on release.
    version = rootProject.findProperty("version") as String

    repositories {
        mavenCentral()
    }
}

val ktlintVersion = libs.versions.ktlint.get()

subprojects {
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    // Formatting gate (runs on `check` via spotlessCheck).
    configure<SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint(ktlintVersion)
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion)
        }
    }

    // Common Kotlin/JVM configuration for any module that applies the plugin.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                // Target JVM 17 so artifacts run on JDK 17+ (Gradle 9 runs on JDK 17+).
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }

    // Keep javac and Kotlin on the same JVM target (both 17) without a separate toolchain.
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }

    // Maven Central publishing (vanniktech). Any module that applies the plugin gets this common
    // config; credentials are supplied only via env (ORG_GRADLE_PROJECT_mavenCentral*/signingInMemory*)
    // in CI — never committed. Coordinates default to (group, project.name, version).
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()
            pom {
                name.set(project.name)
                description.set(provider { project.description })
                url.set("https://github.com/chrisjenx/serialkompat")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("chrisjenx")
                        name.set("Christopher Jenkins")
                    }
                }
                scm {
                    url.set("https://github.com/chrisjenx/serialkompat")
                    connection.set("scm:git:git://github.com/chrisjenx/serialkompat.git")
                    developerConnection.set("scm:git:ssh://git@github.com/chrisjenx/serialkompat.git")
                }
            }
        }
    }
}

// The CLI is an application, not a library, so it has no tracked binary API.
apiValidation {
    ignoredProjects.add("serialkompat-cli")
}

// Docs/code sync gate: every `Rules.*` constant must appear in docs/rules.md so the rule
// matrix can never silently drift from the shipped rule set (see docs/rules.md "Coming later").
val checkRulesDoc = tasks.register("checkRulesDoc") {
    group = "verification"
    description = "Fails if any Rules.* constant is undocumented in docs/rules.md."
    val findingKt = layout.projectDirectory.file(
        "serialkompat-core/src/main/kotlin/com/chrisjenx/serialkompat/core/Finding.kt",
    )
    val rulesDoc = layout.projectDirectory.file("docs/rules.md")
    inputs.file(findingKt)
    inputs.file(rulesDoc)
    doLast {
        val decl = Regex("""const val (\w+)\s*:\s*String\s*=\s*"([A-Z_]+)"""")
        val ruleIds = decl.findAll(findingKt.asFile.readText())
            .map { it.groupValues[2] }
            .filter { it.isNotEmpty() }
            .toList()
        require(ruleIds.isNotEmpty()) { "checkRulesDoc: found no Rules constants — regex/paths wrong?" }
        val docText = rulesDoc.asFile.readText()
        // Substring match: fine while no rule id is a prefix of another. If you ever add a
        // rule whose id contains an existing one (e.g. PROPERTY_ADDED_STRICT), tighten this
        // to a word-boundary/backtick-delimited match so the shorter id can't be masked.
        val missing = ruleIds.filter { !docText.contains(it) }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "docs/rules.md is missing rule row(s): ${missing.joinToString(", ")}. " +
                    "Update the matrix in docs/rules.md when you add or rename a rule.",
            )
        }
        logger.lifecycle("checkRulesDoc: ${ruleIds.size}/${ruleIds.size} rules documented")
    }
}

// The root project has no `check` task by default (no Kotlin/base plugin applied at root);
// apply `base` to get the standard lifecycle and hang the gate on it.
if (tasks.findByName("check") == null) {
    plugins.apply("base")
}
tasks.named("check") { dependsOn(checkRulesDoc) }

// Docs proof gate (issue #119): every oracle test cited in a `**Proof:**` link in
// docs/rules.md must exist, keeping the per-rule proof links from rotting. When
// `enforceComplete` flips true (the #119 sweep, PR 2) it also fails if any Rules.*
// constant lacks a `### `RULE_ID`` Rule reference section. Sibling to checkRulesDoc.
val checkRulesProof = tasks.register("checkRulesProof") {
    group = "verification"
    description = "Verifies docs/rules.md proof links resolve to real oracle tests."
    val findingKt = layout.projectDirectory.file(
        "serialkompat-core/src/main/kotlin/com/chrisjenx/serialkompat/core/Finding.kt",
    )
    val rulesDoc = layout.projectDirectory.file("docs/rules.md")
    val oracleTest = layout.projectDirectory.file(
        "serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt",
    )
    inputs.file(findingKt)
    inputs.file(rulesDoc)
    inputs.file(oracleTest)
    doLast {
        // PR 2 (#119 sweep) flips this true once every rule has a Rule reference section.
        val enforceComplete = false

        // The rule-id extraction below is duplicated from checkRulesDoc on purpose: a shared
        // top-level helper/val can't be captured into a task action under the configuration
        // cache ("cannot serialize Gradle script object references"), so each gate inlines it.
        val decl = Regex("""const val (\w+)\s*:\s*String\s*=\s*"([A-Z_]+)"""")
        val ruleIds = decl.findAll(findingKt.asFile.readText())
            .map { it.groupValues[2] }
            .filter { it.isNotEmpty() }
            .toList()
        require(ruleIds.isNotEmpty()) { "checkRulesProof: found no Rules constants — regex/paths wrong?" }

        val docText = rulesDoc.asFile.readText()

        // Oracle test method names, e.g. fun `removing an optional field`().
        val testNames = Regex("""fun `([^`]+)`""")
            .findAll(oracleTest.asFile.readText())
            .map { it.groupValues[1] }
            .toSet()

        // Test names the docs cite: backtick link text on any `**Proof:**` line. The
        // citation must be backtick-wrapped so the gate can validate it — a proof line
        // with a link but no backtick citation would silently skip the check, so fail it.
        val citeRegex = Regex("""\[`([^`]+)`\]""")
        val proofLines = docText.lineSequence()
            .filter { it.trimStart().startsWith("**Proof:**") }
            .toList()
        val uncited = proofLines.filter { "](" in it && citeRegex.find(it) == null }
        if (uncited.isNotEmpty()) {
            throw GradleException(
                "docs/rules.md has ${uncited.size} **Proof:** line(s) with a link but no " +
                    "backtick-wrapped test citation; wrap the test name in backticks so it is checked.",
            )
        }
        val citedTests = proofLines.flatMap { line -> citeRegex.findAll(line).map { it.groupValues[1] } }
        val missingTests = citedTests.filter { it !in testNames }
        if (missingTests.isNotEmpty()) {
            throw GradleException(
                "docs/rules.md Proof link(s) cite oracle tests that don't exist: " +
                    missingTests.joinToString(", ") +
                    ". Match the link text to a test method name in RoundTripOracleTest.kt.",
            )
        }

        // Rules that already have a `### `RULE_ID`` section.
        val documented = Regex("""### `([A-Z_]+)`""")
            .findAll(docText)
            .map { it.groupValues[1] }
            .toSet()
        val missingSections = ruleIds.filter { it !in documented }
        if (missingSections.isNotEmpty()) {
            val gaps = missingSections.joinToString(", ")
            if (enforceComplete) {
                throw GradleException("docs/rules.md has no Rule reference section for: $gaps (issue #119).")
            }
            logger.lifecycle("checkRulesProof (warn, not yet enforced): missing section(s): $gaps.")
        }
        logger.lifecycle(
            "checkRulesProof: ${citedTests.size} proof link(s) valid, " +
                "${documented.size}/${ruleIds.size} rules documented.",
        )
    }
}
tasks.named("check") { dependsOn(checkRulesProof) }

// Aggregate test coverage and API docs across the library modules.
dependencies {
    kover(project(":serialkompat-core"))
    kover(project(":serialkompat-extractor"))
    kover(project(":serialkompat-gradle"))
    kover(project(":serialkompat-cli"))

    dokka(project(":serialkompat-core"))
    dokka(project(":serialkompat-extractor"))
    dokka(project(":serialkompat-gradle"))
}
