/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import com.github.spotbugs.SpotBugsTask
import com.github.vlsi.gradle.crlf.CrLfSpec
import com.github.vlsi.gradle.crlf.LineEndings
import com.github.vlsi.gradle.git.FindGitAttributes
import com.github.vlsi.gradle.git.dsl.gitignore
import com.github.vlsi.gradle.properties.dsl.lastEditYear
import com.github.vlsi.gradle.properties.dsl.props
import com.github.vlsi.gradle.properties.dsl.toBool
import com.github.vlsi.gradle.publishing.dsl.simplifyXml
import com.github.vlsi.gradle.publishing.dsl.versionFromResolution
import com.github.vlsi.gradle.test.dsl.printTestResults
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApisExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    publishing
    // Verification
    checkstyle
    id("com.github.autostyle")
    id("org.nosphere.apache.rat")
    id("com.github.spotbugs")
    id("de.thetaphi.forbiddenapis") apply false
    id("org.owasp.dependencycheck")
    id("com.github.johnrengelman.shadow") apply false
    // IDE configuration
    id("org.jetbrains.gradle.plugin.idea-ext")
    id("com.github.vlsi.ide")
    // Release
    id("com.github.vlsi.crlf")
    id("com.github.vlsi.gradle-extensions")
    id("com.github.vlsi.license-gather") apply false
    signing
}

repositories {
    // At least for RAT
    mavenCentral()
}

fun reportsForHumans() = !System.getenv()["CI"].toBool(default = false)

val lastEditYear by extra(lastEditYear())

// Do not enable spotbugs by default. Execute it only when -Pspotbugs is present
val enableSpotBugs = props.bool("spotbugs", default = false)
val skipCheckstyle by props()
val skipAutostyle by props()
val skipJavadoc by props()
// Inherited from stage-vote-release-plugin: skipSign, useGpgCmd
val enableMavenLocal by props()
val enableGradleMetadata by props()

ide {
    copyrightToAsf()
    ideaInstructionsUri =
        uri("https://github.com/apache/calcite-avatica/blob/master/CONTRIBUTING.md#intellij")
    doNotDetectFrameworks("android", "jruby")
}

// This task scans the project for gitignore / gitattributes, and that is reused for building
// source/binary artifacts with the appropriate eol/executable file flags
// It enables to automatically exclude patterns from .gitignore
val gitProps by tasks.registering(FindGitAttributes::class) {
    // Scanning for .gitignore and .gitattributes files in a task avoids doing that
    // when distribution build is not required (e.g. code is just compiled)
    root.set(rootDir)
}

val rat by tasks.getting(org.nosphere.apache.rat.RatTask::class) {
    gitignore(gitProps)
    verbose.set(true)
    // Note: patterns are in non-standard syntax for RAT, so we use exclude(..) instead of excludeFile
    exclude(rootDir.resolve(".ratignore").readLines())
}

val String.v: String get() = rootProject.extra["$this.version"] as String

// val buildVersion = "calcite.avatica".v + releaseParams.snapshotSuffix
val buildVersion = "calcite.avatica".v

println("Building Avatica for Polypheny $buildVersion")

val javadocAggregate by tasks.registering(Javadoc::class) {
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    description = "Generates aggregate javadoc for all the artifacts"

    val sourceSets = allprojects
        .mapNotNull { it.extensions.findByType<SourceSetContainer>() }
        .map { it.named("main") }

    classpath = files(sourceSets.map { set -> set.map { it.output + it.compileClasspath } })
    setSource(sourceSets.map { set -> set.map { it.allJava } })
    setDestinationDir(file("$buildDir/docs/javadocAggregate"))
}

/** Similar to {@link #javadocAggregate} but includes tests.
 * CI uses this target to validate javadoc (e.g. checking for broken links). */
val javadocAggregateIncludingTests by tasks.registering(Javadoc::class) {
    description = "Generates aggregate javadoc for all the artifacts, including test code"

    val sourceSets = allprojects
        .mapNotNull { it.extensions.findByType<SourceSetContainer>() }
        .flatMap { listOf(it.named("main"), it.named("test")) }

    classpath = files(sourceSets.map { set -> set.map { it.output + it.compileClasspath } })
    setSource(sourceSets.map { set -> set.map { it.allJava } })
    setDestinationDir(file("$buildDir/docs/javadocAggregateIncludingTests"))
}

allprojects {
    group = "org.polypheny.avatica"
    version = buildVersion

    repositories {
        // RAT and Autostyle dependencies
        mavenCentral()
    }

    plugins.withId("java-library") {
        dependencies {
            "implementation"(platform(project(":bom")))
        }
    }
    if (!skipAutostyle) {
        apply(plugin = "com.github.autostyle")
        autostyle {
            fun com.github.autostyle.gradle.BaseFormatExtension.license() {
                // Exclude Jenkins' "?" directory
                filter.exclude("?/**")
                licenseHeader(rootProject.ide.licenseHeader)
                trimTrailingWhitespace()
                endWithNewline()
            }
            kotlinGradle {
                // Exclude Jenkins' "?" directory
                filter.exclude("?/**")
                license()
                ktlint()
            }
            format("configs") {
                filter {
                    include("**/*.sh", "**/*.bsh", "**/*.cmd", "**/*.bat")
                    include("**/*.properties", "**/*.yml")
                    include("**/*.xsd", "**/*.xsl", "**/*.xml")
                    // Autostyle does not support gitignore yet https://github.com/autostyle/autostyle/issues/13
                    exclude("bin/**", "out/**", "gradlew*")
                    // Exclude Jenkins' "?" directory
                    exclude("?/**")
                }
                license()
            }
            format("markdown") {
                filter {
                    include("**/*.md")
                    // Exclude Jenkins' "?" directory
                    exclude("?/**")
                }
                endWithNewline()
            }
        }
    }
    if (!skipCheckstyle) {
        apply<CheckstylePlugin>()
        dependencies {
            checkstyle("com.puppycrawl.tools:checkstyle:${"checkstyle".v}")
            checkstyle("net.hydromatic:toolbox:${"hydromatic-toolbox".v}")
        }
        checkstyle {
            // Current one is ~8.8
            // https://github.com/julianhyde/toolbox/issues/3
            //  toolVersion = "6.18"
            isShowViolations = true
            val dir = File(rootDir, "src/main/config/checkstyle")
            configDirectory.set(dir)
            configFile = File(dir, "checker.xml")
            configProperties = mapOf(
                "checkstyle.header.file" to "$rootDir/src/main/config/checkstyle/header.txt",
                "checkstyle.suppressions.file" to "$rootDir/src/main/config/checkstyle/suppressions.xml"
            )
        }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        // Ensure builds are reproducible
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        dirMode = "775".toInt(8)
        fileMode = "664".toInt(8)
    }

    tasks {
        withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).apply {
                // Please refrain from using non-ASCII chars below since the options are passed as
                // javadoc.options file which is parsed with "default encoding"
                noTimestamp.value = true
                showFromProtected()
                // javadoc: error - The code being documented uses modules but the packages
                // defined in https://docs.oracle.com/javase/9/docs/api/ are in the unnamed module
                source = "1.8"
                docEncoding = "UTF-8"
                charSet = "UTF-8"
                encoding = "UTF-8"
                docTitle = "Avatica for Polypheny ${project.name} API"
                windowTitle = "Avatica for Polypheny ${project.name} API"
                header = "<b>Avatica for Polypheny</b>"
                bottom = "Copyright &copy; 2012-$lastEditYear Apache Software Foundation. All Rights Reserved.<br>Copyright &copy; 2019-$lastEditYear The Polypheny Project"
                if (JavaVersion.current() >= JavaVersion.VERSION_1_9) {
                    addBooleanOption("html5", true)
                    links("https://docs.oracle.com/javase/9/docs/api/")
                } else {
                    links("https://docs.oracle.com/javase/8/docs/api/")
                }
            }
        }
    }

    plugins.withType<JavaPlugin> {
        configure<JavaPluginConvention> {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        repositories {
            if (enableMavenLocal) {
                mavenLocal()
            }
            mavenCentral()
        }
        val sourceSets: SourceSetContainer by project

        apply(plugin = "de.thetaphi.forbiddenapis")
        apply(plugin = "maven-publish")

        if (!enableGradleMetadata) {
            tasks.withType<GenerateModuleMetadata> {
                enabled = false
            }
        }

        if (!skipAutostyle) {
            autostyle {
                java {
                    // Exclude Jenkins' "?" directory
                    filter.exclude("?/**")
                    licenseHeader(rootProject.ide.licenseHeader)
                    importOrder(
                        "org.apache.calcite.",
                        "org.apache.",
                        "au.com.",
                        "com.",
                        "io.",
                        "mondrian.",
                        "net.",
                        "org.",
                        "scala.",
                        "java",
                        "",
                        "static com.",
                        "static org.apache.calcite.",
                        "static org.apache.",
                        "static org.",
                        "static java",
                        "static "
                    )
                    replaceRegex("side by side comments", "(\n\\s*+[*]*+/\n)(/[/*])", "\$1\n\$2")
                    removeUnusedImports()
                    trimTrailingWhitespace()
                    indentWithSpaces(2)
                    endWithNewline()
                }
            }
        }
        if (enableSpotBugs) {
            apply(plugin = "com.github.spotbugs")
            spotbugs {
                toolVersion = "spotbugs".v
                reportLevel = "high"
                excludeFilter = file("$rootDir/src/main/config/spotbugs/spotbugs-filter.xml")
                // By default spotbugs verifies TEST classes as well, and we do not want that
                this.sourceSets = listOf(sourceSets["main"])
            }
            dependencies {
                constraints {
                    "spotbugs"("org.ow2.asm:asm:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-all:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-analysis:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-commons:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-tree:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-util:${"asm".v}")
                }
            }
        }

        configure<CheckForbiddenApisExtension> {
            failOnUnsupportedJava = false
            bundledSignatures.addAll(
                listOf(
                    "jdk-unsafe",
                    "jdk-deprecated",
                    "jdk-non-portable"
                )
            )
            signaturesFiles = files("$rootDir/src/main/config/forbidden-apis/signatures.txt")
        }

        (sourceSets) {
            "main" {
                resources {
                    // TODO: remove when LICENSE is removed (it is used by Maven build for now)
                    exclude("src/main/resources/META-INF/LICENSE")
                }
            }
        }

        tasks {
            withType<Jar>().configureEach {
                manifest {
                    attributes["Bundle-License"] = "Apache-2.0"
                    attributes["Implementation-Title"] = "Avatica for Polypheny"
                    attributes["Implementation-Version"] = project.version
                    attributes["Specification-Vendor"] = "The Polypheny Project"
                    attributes["Specification-Version"] = project.version
                    attributes["Specification-Title"] = "Avatica for Polypheny"
                    attributes["Implementation-Vendor"] = "The Polypheny Project"
                    attributes["Implementation-Vendor-Id"] = "org.polypheny.avatica"
                }
            }

            withType<CheckForbiddenApis>().configureEach {
                exclude(
                    "**/org/apache/calcite/avatica/tck/Unsafe.class",
                    "**/org/apache/calcite/avatica/util/Unsafe.class"
                )
            }

            withType<JavaCompile>().configureEach {
                options.encoding = "UTF-8"
            }
            withType<Test>().configureEach {
                testLogging {
                    exceptionFormat = TestExceptionFormat.FULL
                    showStandardStreams = true
                }
                // Pass the property to tests
                fun passProperty(name: String, default: String? = null) {
                    val value = System.getProperty(name) ?: default
                    value?.let { systemProperty(name, it) }
                }
                passProperty("java.awt.headless")
                passProperty("user.language", "TR")
                passProperty("user.country", "tr")
                val props = System.getProperties()
                for (e in props.propertyNames() as `java.util`.Enumeration<String>) {
                    if (e.startsWith("calcite.") || e.startsWith("avatica.")) {
                        passProperty(e)
                    }
                }
                printTestResults()
            }
            withType<SpotBugsTask>().configureEach {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                if (enableSpotBugs) {
                    description = "$description (skipped by default, to enable it add -Dspotbugs)"
                }
                reports {
                    html.isEnabled = reportsForHumans()
                    xml.isEnabled = !reportsForHumans()
                }
                enabled = enableSpotBugs
            }

            afterEvaluate {
                // Add default license/notice when missing
                withType<Jar>().configureEach {
                    CrLfSpec(LineEndings.LF).run {
                        into("META-INF") {
                            filteringCharset = "UTF-8"
                            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                            // Note: we need "generic Apache-2.0" text without third-party items
                            // So we use the text from $rootDir/config/ since source distribution
                            // contains altered text at $rootDir/LICENSE
                            textFrom("$rootDir/src/main/config/licenses/LICENSE")
                            textFrom("$rootDir/NOTICE")
                        }
                    }
                }
            }
        }

        // Note: jars below do not normalize line endings.
        // Those jars, however are not included to source/binary distributions
        // so the normalization is not that important

        val testJar by tasks.registering(Jar::class) {
            from(sourceSets["test"].output)
            archiveClassifier.set("tests")
        }

        val testSourcesJar by tasks.registering(Jar::class) {
            from(sourceSets["test"].allJava)
            archiveClassifier.set("test-sources")
        }

        val sourcesJar by tasks.registering(Jar::class) {
            from(sourceSets["main"].allJava)
            archiveClassifier.set("sources")
        }

        val javadocJar by tasks.registering(Jar::class) {
            from(tasks.named(JavaPlugin.JAVADOC_TASK_NAME))
            archiveClassifier.set("javadoc")
        }

        val testClasses by configurations.creating {
            extendsFrom(configurations["testRuntime"])
        }

        val archives by configurations.getting

        // Parenthesis needed to use Project#getArtifacts
        (artifacts) {
            testClasses(testJar)
            archives(sourcesJar)
            archives(testJar)
            archives(testSourcesJar)
        }

        val archivesBaseName = when (path) {
            ":shaded:avatica" -> "avatica"
            else -> "avatica-$name"
        }
        setProperty("archivesBaseName", archivesBaseName)

        /*signing {
            setRequired({
                gradle.taskGraph.hasTask("publish")
            })
            val signingKey: String? by project
            val signingPassword: String? by project
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(publishing.publications["mavenJava"])
        }*/

        publishing {
            publications {
                create<MavenPublication>(project.name) {
                    artifactId = archivesBaseName
                    version = rootProject.version.toString()
                    description = project.description
                    from(components["java"])

                    if (!skipJavadoc) {
                        // Eager task creation is required due to
                        // https://github.com/gradle/gradle/issues/6246
                        artifact(sourcesJar.get())
                        artifact(javadocJar.get())
                    }

                    versionFromResolution()
                    pom {
                        simplifyXml()
                        project.property("artifact.name")?.let { name.set(it as String) }
                        description.set("Fork of Apache Calcite Avatica with Polypheny-DB specific adjustments.")
                        inceptionYear.set("2012")
                        url.set("https://polypheny.org/")
                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                                comments.set("A business-friendly OSS license")
                                distribution.set("repo")
                            }
                        }
                        issueManagement {
                            system.set("GitHub")
                            url.set("https://github.com/polypheny/Polypheny-DB/issues")
                        }
                        scm {
                            connection.set("scm:git:https://github.com/polypheny/Avatica.git")
                            url.set("https://github.com/polypheny/Avatica")
                        }
                        developers {
                            developer {
                                id.set("polypheny")
                                name.set("Polypheny")
                                email.set("mail@polypheny.org")
                            }
                        }
                    }
                }
            }
            repositories {
                /*maven {
                    name = "OSSRH"
                    val releasesRepoUrl = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                    val snapshotsRepoUrl = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                    url =  uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
                    credentials {
                        username = System.getenv("MAVEN_USERNAME")
                        password = System.getenv("MAVEN_PASSWORD")
                    }
                }*/
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/polypheny/avatica")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR")
                        password = System.getenv("GITHUB_TOKEN")
                    }
                }
                maven {
                    name = "DBIS_Nexus"
                    val releasesRepoUrl = "https://dbis-nexus.dmi.unibas.ch/repository/maven-releases/"
                    val snapshotsRepoUrl = "https://dbis-nexus.dmi.unibas.ch/repository/maven-snapshots/"
                    url =  uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
                    credentials {
                        username = System.getenv("DBIS_NEXUS_USERNAME")
                        password = System.getenv("DBIS_NEXUS_PASSWORD")
                    }
                }
            }
        }
    }
}
