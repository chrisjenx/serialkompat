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
// docs-samples is unpublished docs example code (#110), so it has none either.
apiValidation {
    ignoredProjects.add("serialkompat-cli")
    ignoredProjects.add("docs-samples")
}

// Docs/code sync gate: every `Rules.*` constant must appear in docs/rules.md so the rule
// matrix can never silently drift from the shipped rule set (see CONTRIBUTING.md "Docs site").
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

// Docs proof gate (issue #119): every test cited in a `**Proof:**` link in docs/rules.md
// must exist, keeping the per-rule proof links from rotting. With `enforceComplete` it also
// fails if any Rules.* constant lacks a `### `RULE_ID`` Rule reference section, or if a
// section has no `**Proof:**` line. Sibling to checkRulesDoc.
val checkRulesProof = tasks.register("checkRulesProof") {
    group = "verification"
    description = "Verifies every rule in docs/rules.md has a section whose proof links resolve to real tests."
    val findingKt = layout.projectDirectory.file(
        "serialkompat-core/src/main/kotlin/com/chrisjenx/serialkompat/core/Finding.kt",
    )
    val rulesDoc = layout.projectDirectory.file("docs/rules.md")
    // The test files a Proof line may cite: the JSON and CBOR round-trip oracles, plus the unit tests
    // behind verdicts no round-trip can exercise (an unserializable model, an unrecorded fact).
    val extractorTests = "serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor"
    val coreTests = "serialkompat-core/src/test/kotlin/com/chrisjenx/serialkompat/core"
    val proofTestPaths =
        listOf(
            "$extractorTests/RoundTripOracleTest.kt",
            "$extractorTests/CborRoundTripOracleTest.kt",
            "$extractorTests/ToleranceGapsTest.kt",
            "$coreTests/DiscriminatorCollisionTest.kt",
            "$coreTests/ClassifierTest.kt",
            "$coreTests/ClassifierConfigTest.kt",
            "$coreTests/CborRulesTest.kt",
        )
    val proofTests = proofTestPaths.associateWith { layout.projectDirectory.file(it) }
    val proofTestNames = proofTestPaths.joinToString(", ") { it.substringAfterLast('/') }
    inputs.file(findingKt)
    inputs.file(rulesDoc)
    inputs.files(proofTests.values)
    doLast {
        // On since the #119 sweep: every rule needs a Rule reference section with a Proof line.
        val enforceComplete = true

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

        // Test method names per proof file, e.g. fun `removing an optional field`().
        val testNames = proofTests.mapValues { (_, file) ->
            Regex("""fun `([^`]+)`""").findAll(file.asFile.readText()).map { it.groupValues[1] }.toSet()
        }

        // Tests the docs cite: a backtick-wrapped link `[`test name`](…/<test file>)` on a
        // `**Proof:**` line. A proof line with a link but no such citation would silently skip
        // the check, so fail it.
        val citeRegex = Regex("""\[`([^`]+)`\]\(([^)\s]+)\)""")
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
        // Each cited name must be a test method in the very file its link points at.
        val citedTests = proofLines.flatMap { line ->
            citeRegex.findAll(line).map { it.groupValues[1] to it.groupValues[2] }
        }
        val missingTests = citedTests.filter { (name, url) ->
            val path = proofTests.keys.firstOrNull { url.endsWith("/$it") }
            path == null || name !in testNames.getValue(path)
        }
        if (missingTests.isNotEmpty()) {
            throw GradleException(
                "docs/rules.md Proof link(s) cite tests that don't exist in the linked file: " +
                    missingTests.joinToString(", ") { (name, url) -> "`$name` (${url.substringAfterLast('/')})" } +
                    ". Link each test name to the file that declares it, one of: $proofTestNames.",
            )
        }

        // Rules that already have a `### `RULE_ID`` section, each with the body up to the next heading.
        val sectionRegex = Regex("""^### `([A-Z_]+)`.*$""", RegexOption.MULTILINE)
        val headingRegex = Regex("""^#{1,3} """, RegexOption.MULTILINE)
        val sections = sectionRegex.findAll(docText).associate { match ->
            val bodyStart = match.range.last + 1
            val bodyEnd = headingRegex.find(docText, bodyStart)?.range?.first ?: docText.length
            match.groupValues[1] to docText.substring(bodyStart, bodyEnd)
        }
        val documented = sections.keys
        val missingSections = ruleIds.filter { it !in documented }
        if (missingSections.isNotEmpty()) {
            val gaps = missingSections.joinToString(", ")
            if (enforceComplete) {
                throw GradleException("docs/rules.md has no Rule reference section for: $gaps (issue #119).")
            }
            logger.lifecycle("checkRulesProof (warn, not yet enforced): missing section(s): $gaps.")
        }
        // Every rule section must prove its verdict: a `**Proof:**` line citing at least one test.
        val unproven = sections.filter { (_, body) ->
            body.lineSequence().none { it.trimStart().startsWith("**Proof:**") && citeRegex.containsMatchIn(it) }
        }.keys
        if (unproven.isNotEmpty()) {
            val gaps = unproven.joinToString(", ")
            if (enforceComplete) {
                throw GradleException("docs/rules.md Rule reference section(s) with no cited test: $gaps.")
            }
            logger.lifecycle("checkRulesProof (warn, not yet enforced): section(s) with no cited test: $gaps.")
        }
        logger.lifecycle(
            "checkRulesProof: ${citedTests.size} proof link(s) valid, " +
                "${documented.size}/${ruleIds.size} rules documented.",
        )
    }
}
tasks.named("check") { dependsOn(checkRulesProof) }

// Docs reference gate (docs redesign): every public Gradle DSL property/block, task name, -P property
// and CLI flag must appear in the docs as a backticked token, so the reference pages cannot drift
// from the code. Sibling to checkRulesDoc. Each extraction is inlined (config cache: no shared helpers).
val checkDocsReference = tasks.register("checkDocsReference") {
    group = "verification"
    description = "Fails if a DSL property, task, -P property or CLI flag is undocumented."
    val gradleDir = "serialkompat-gradle/src/main/kotlin/com/chrisjenx/serialkompat/gradle"
    val dslFiles = listOf("SerialkompatExtension.kt", "SerialkompatHistory.kt", "SerialkompatReports.kt")
        .map { layout.projectDirectory.file("$gradleDir/$it") }
    val pluginKt = layout.projectDirectory.file("$gradleDir/SerialkompatPlugin.kt")
    val cliKt = layout.projectDirectory.file(
        "serialkompat-cli/src/main/kotlin/com/chrisjenx/serialkompat/cli/SerialkompatCli.kt",
    )
    val configDoc = layout.projectDirectory.file("docs/configuration.md")
    val tasksDoc = layout.projectDirectory.file("docs/tasks-cli.md")
    inputs.files(dslFiles)
    inputs.file(pluginKt)
    inputs.file(cliKt)
    inputs.file(configDoc)
    inputs.file(tasksDoc)
    doLast {
        // Documented as bare names (extension members, the `history`/`reports` block funs) or as
        // dotted table paths (`history.dir`, `reports.json.required`): history members get a
        // `history.` prefix; report members get `reports.<format>.` for each format sub-block fun.
        val valRe = Regex("""public abstract val (\w+):""")
        val funRe = Regex("""public fun (\w+)\(action""")
        val extText = dslFiles[0].asFile.readText()
        val histText = dslFiles[1].asFile.readText()
        val repText = dslFiles[2].asFile.readText()
        val formats = funRe.findAll(repText).map { it.groupValues[1] }.toList()
        val repMembers = valRe.findAll(repText).map { it.groupValues[1] }.toList()
        val histMembers = valRe.findAll(histText).map { it.groupValues[1] }.toList()
        // Fail closed: a reformatted sub-block file must not silently drop its rows from the gate.
        require(formats.isNotEmpty() && repMembers.isNotEmpty()) {
            "checkDocsReference: found no reports{} formats/members — regex/paths wrong?"
        }
        require(histMembers.isNotEmpty()) { "checkDocsReference: found no history{} members — regex/paths wrong?" }
        val dsl = (
            valRe.findAll(extText).map { it.groupValues[1] } +
                funRe.findAll(extText).map { it.groupValues[1] } +
                histMembers.map { "history.$it" } +
                formats.flatMap { f -> repMembers.map { m -> "reports.$f.$m" } }
            ).distinct().toList()
        require(dsl.isNotEmpty()) { "checkDocsReference: found no DSL members — regex/paths wrong?" }

        val pluginText = pluginKt.asFile.readText()
        val taskNames = Regex("""TASK_NAME: String = "(\w+)"""").findAll(pluginText)
            .map { it.groupValues[1] }.distinct().toList()
        require(taskNames.isNotEmpty()) { "checkDocsReference: found no task names — regex/paths wrong?" }

        // `private`/`internal`/`public` const forms all match; the .internal. test-only hook is excluded.
        val props = Regex("""_PROPERTY: String = "(serialkompat\.[\w.]+)"""").findAll(pluginText)
            .map { it.groupValues[1] }.filter { ".internal." !in it }.distinct().toList()
        require(props.isNotEmpty()) { "checkDocsReference: found no -P properties — regex/paths wrong?" }

        val optionsLine = Regex("""KNOWN_OPTIONS = setOf\(([^)]*)\)""").find(cliKt.asFile.readText())
        val flags = optionsLine?.let { m ->
            Regex(""""([^"]+)"""").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
        }.orEmpty()
        require(flags.isNotEmpty()) { "checkDocsReference: found no CLI flags — regex/paths wrong?" }

        // Exact backticked token: `name` (a bare-word/substring match would let `required` pass
        // via `failOnEmptyBaseline`). A token followed by extra text inside the ticks, e.g.
        // `renames.put(...)`, does not count; document the bare name. -P properties are written as
        // `-Pname=<value>` in the docs, so those match `-P<name>` followed by `=` or a closing tick.
        val configText = configDoc.asFile.readText()
        val tasksText = tasksDoc.asFile.readText()
        val misses = mutableListOf<String>()
        dsl.filter { "`$it`" !in configText }
            .forEach { misses += "DSL $it (add it to docs/configuration.md)" }
        taskNames.filter { "`$it`" !in tasksText }
            .forEach { misses += "task $it (add it to docs/tasks-cli.md)" }
        props.filter { !Regex("""`-P${Regex.escape(it)}[=`]""").containsMatchIn(tasksText) }
            .forEach { misses += "property $it (add it to docs/tasks-cli.md)" }
        flags.filter { !Regex("""`${Regex.escape(it)}[=`]""").containsMatchIn(tasksText) }
            .forEach { misses += "CLI flag $it (add it to docs/tasks-cli.md)" }
        if (misses.isNotEmpty()) {
            throw GradleException(
                misses.joinToString("\n") { "docs reference is missing: $it" },
            )
        }
        logger.lifecycle(
            "checkDocsReference: ${dsl.size} DSL, ${taskNames.size} tasks, ${props.size} properties, " +
                "${flags.size} CLI flags documented",
        )
    }
}
tasks.named("check") { dependsOn(checkDocsReference) }

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
