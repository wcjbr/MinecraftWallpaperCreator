import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.0"
    id("fabric-loom") version "1.17.12"
    id("maven-publish")
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val rustWorkspaceDir = layout.projectDirectory.dir("rust")
val rustWorkerResourceDir = layout.buildDirectory.dir("generated/rust-worker-resources")
val generatedMappingsDir = layout.buildDirectory.dir("generated/mappings").get().asFile.toPath()
val generatedMappingsTiny = generatedMappingsDir.resolve("mappings.tiny")
val generatedMappingsJar = generatedMappingsDir.resolve("placeholder-mappings.jar")
val hmclClothConfigJar = providers.gradleProperty("hmcl_cloth_config_jar")
    .orElse("/home/archzero/.config/hmcl/.minecraft/versions/XPlus PerioTable based on Minecraft 26.1.2 (Fabric)/mods/cloth-config-26.1.154.jar")
val hmclBasicMathJar = providers.gradleProperty("hmcl_basic_math_jar")
    .orElse("/home/archzero/.gradle/caches/modules-2/files-2.1/me.shedaniel.cloth/basic-math/0.6.1/2ddd64b22126332794a5754ff9b942e17fb44c39/basic-math-0.6.1.jar")

data class RustBundleTarget(
    val triple: String,
    val resourcePlatformDir: String,
    val binaryName: String,
    val linkerEnvName: String? = null,
    val linkerPropertyName: String? = null,
    val defaultLinker: String? = null
) {
    val taskSuffix: String = triple.split('-', '_')
        .joinToString("") { part ->
            part.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
            }
        }

    val outputRelativePath: String
        get() = "target/$triple/release/$binaryName"

    val resourcePath: String
        get() = "minecraftwallpapercreater/native/$resourcePlatformDir"
}

val rustBundleTargets = listOf(
    RustBundleTarget(
        triple = "x86_64-unknown-linux-gnu",
        resourcePlatformDir = "linux-x86_64",
        binaryName = "wallpaper-worker"
    ),
    RustBundleTarget(
        triple = "aarch64-unknown-linux-gnu",
        resourcePlatformDir = "linux-aarch64",
        binaryName = "wallpaper-worker",
        linkerEnvName = "CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER",
        linkerPropertyName = "rust_linker_aarch64_unknown_linux_gnu",
        defaultLinker = "aarch64-linux-gnu-gcc"
    ),
    RustBundleTarget(
        triple = "x86_64-pc-windows-gnu",
        resourcePlatformDir = "windows-x86_64",
        binaryName = "wallpaper-worker.exe",
        linkerEnvName = "CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER",
        linkerPropertyName = "rust_linker_x86_64_pc_windows_gnu",
        defaultLinker = "x86_64-w64-mingw32-gcc"
    ),
    RustBundleTarget(
        triple = "x86_64-apple-darwin",
        resourcePlatformDir = "macos-x86_64",
        binaryName = "wallpaper-worker",
        linkerEnvName = "CARGO_TARGET_X86_64_APPLE_DARWIN_LINKER",
        linkerPropertyName = "rust_linker_x86_64_apple_darwin"
    ),
    RustBundleTarget(
        triple = "aarch64-apple-darwin",
        resourcePlatformDir = "macos-aarch64",
        binaryName = "wallpaper-worker",
        linkerEnvName = "CARGO_TARGET_AARCH64_APPLE_DARWIN_LINKER",
        linkerPropertyName = "rust_linker_aarch64_apple_darwin"
    )
)

val requestedRustBundleTargets = (findProperty("rust_bundle_targets") as String?)
    ?.split(',')
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.toSet()
    ?: rustBundleTargets.map { it.triple }.toSet()

val selectedRustBundleTargets = rustBundleTargets.filter { it.triple in requestedRustBundleTargets }
val hostRustBundleTarget = run {
    val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
    val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
    rustBundleTargets.firstOrNull { target ->
        when (target.triple) {
            "x86_64-unknown-linux-gnu" -> osName.contains("linux") && (arch == "x86_64" || arch == "amd64")
            "aarch64-unknown-linux-gnu" -> osName.contains("linux") && (arch == "aarch64" || arch == "arm64")
            "x86_64-pc-windows-gnu" -> osName.contains("windows") && (arch == "x86_64" || arch == "amd64")
            "x86_64-apple-darwin" -> osName.contains("mac") && (arch == "x86_64" || arch == "amd64")
            "aarch64-apple-darwin" -> osName.contains("mac") && (arch == "aarch64" || arch == "arm64")
            else -> false
        }
    }
}

check(selectedRustBundleTargets.isNotEmpty()) {
    "No rust bundle targets selected. Set -Prust_bundle_targets=<comma-separated target triples>."
}

val unknownRustTargets = requestedRustBundleTargets - rustBundleTargets.map { it.triple }.toSet()
check(unknownRustTargets.isEmpty()) {
    "Unknown rust bundle targets requested: ${unknownRustTargets.joinToString(", ")}"
}

fun patchRuntimeNamespaceInJar(jarPath: Path): Boolean {
    if (!Files.exists(jarPath)) {
        return false
    }

    ZipFile(jarPath.toFile()).use { zip ->
        val replacements = mutableMapOf<String, ByteArray>()

        zip.entries().asSequence().forEach { entry ->
            val name = entry.name
            val isTweaker = name.endsWith(".classtweaker")
                || name.endsWith(".accessWidener")
                || name.endsWith(".accesswidener")

            if (!isTweaker) {
                return@forEach
            }

            val original = zip.getInputStream(entry).use { it.readBytes() }
            val text = original.toString(StandardCharsets.UTF_8)
            val lines = text.split('\n').toMutableList()
            if (lines.isEmpty()) {
                return@forEach
            }

            val firstLine = lines.first()
            val patchedLine = when {
                firstLine.contains("\tnamed") -> firstLine.replace("\tnamed", "\tofficial")
                firstLine.endsWith(" named") -> firstLine.removeSuffix(" named") + " official"
                else -> firstLine
            }

            if (patchedLine == firstLine) {
                return@forEach
            }

            lines[0] = patchedLine
            var patched = lines.joinToString("\n")
            if (text.endsWith("\n")) {
                patched += "\n"
            }
            replacements[name] = patched.toByteArray(StandardCharsets.UTF_8)
        }

        if (replacements.isEmpty()) {
            return false
        }

        val tempJar = Files.createTempFile(jarPath.parent, "mwc-runtime-", ".jar")
        ZipOutputStream(Files.newOutputStream(tempJar)).use { output ->
            zip.entries().asSequence().forEach { entry ->
                val nextEntry = ZipEntry(entry.name).apply {
                    method = ZipEntry.DEFLATED
                    comment = entry.comment
                    time = entry.time
                    extra = entry.extra
                }
                output.putNextEntry(nextEntry)
                output.write(replacements[entry.name] ?: zip.getInputStream(entry).use { it.readBytes() })
                output.closeEntry()
            }
        }

        Files.move(tempJar, jarPath, StandardCopyOption.REPLACE_EXISTING)
        return true
    }
}

run {
    Files.createDirectories(generatedMappingsDir)
    Files.writeString(
        generatedMappingsTiny,
        "tiny\t2\t0\tofficial\tnamed\n",
        StandardCharsets.UTF_8
    )

    Files.deleteIfExists(generatedMappingsJar)

    ZipOutputStream(Files.newOutputStream(generatedMappingsJar)).use { zip ->
        zip.putNextEntry(ZipEntry("mappings/mappings.tiny"))
        Files.copy(generatedMappingsTiny, zip)
        zip.closeEntry()
    }
}

val buildRustWorkerTasks = selectedRustBundleTargets.associateWith { target ->
    tasks.register<Exec>("buildRustWorker${target.taskSuffix}") {
        group = "build"
        description = "Builds the Rust wallpaper worker for ${target.triple}."
        workingDir = rustWorkspaceDir.asFile
        commandLine(
            "cargo", "build", "--release",
            "--target", target.triple,
            "--manifest-path", "Cargo.toml",
            "-p", "wallpaper-worker"
        )
        inputs.files(
            fileTree(rustWorkspaceDir) {
                include("**/*.rs")
                include("**/Cargo.toml")
                include("**/Cargo.lock")
            }
        )
        outputs.file(rustWorkspaceDir.file(target.outputRelativePath))

        doFirst {
            val linkerEnvName = target.linkerEnvName
            val linkerPropertyName = target.linkerPropertyName
            if (linkerEnvName == null || linkerPropertyName == null) {
                return@doFirst
            }

            val configuredLinker = (findProperty(linkerPropertyName) as String?)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: target.defaultLinker

            check(!configuredLinker.isNullOrBlank()) {
                "Missing linker for ${target.triple}. Set -P${linkerPropertyName}=<linker-path>."
            }

            environment(linkerEnvName, configuredLinker)
            logger.lifecycle("Building Rust worker for {} using linker {}", target.triple, configuredLinker)
        }
    }
}

val buildRustWorkers by tasks.registering {
    group = "build"
    description = "Builds the Rust wallpaper worker for all selected bundle targets."
    dependsOn(buildRustWorkerTasks.values)
}

val syncRustWorkerResources by tasks.registering(Sync::class) {
    group = "build"
    description = "Copies Rust wallpaper workers into generated resources for packaging."
    dependsOn(buildRustWorkers)
    into(rustWorkerResourceDir)

    selectedRustBundleTargets.forEach { target ->
        from(rustWorkspaceDir.file(target.outputRelativePath)) {
            into(target.resourcePath)
        }
    }
}

val patchOfficialRuntimeTweakers by tasks.registering {
    group = "loom"
    description = "Patches remapped runtime tweaker/access widener headers to the official namespace."

    doLast {
        val roots = listOf(
            layout.projectDirectory.dir(".gradle/loom-cache/remapped_mods").asFile.toPath(),
            layout.buildDirectory.dir("loom-cache/remapped_working").get().asFile.toPath()
        )

        var patchedCount = 0
        roots.filter(Files::isDirectory).forEach { root ->
            Files.walk(root).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .forEach { jar ->
                        if (patchRuntimeNamespaceInJar(jar)) {
                            patchedCount++
                        }
                    }
            }
        }

        logger.lifecycle("Patched {} remapped runtime jar(s) to official namespace", patchedCount)
    }
}

val targetJavaVersion = 25
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    // Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
    // if it is present.
    // If you remove this line, sources will not be generated.
    withSourcesJar()
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("minecraftwallpapercreater") {
            sourceSet("main")
            sourceSet("client")
        }
    }
}

fabricApi {
    configureDataGeneration {
        client = true
    }
}

repositories {
    maven("https://maven.terraformersmc.com/releases/")
    maven("https://maven.shedaniel.me/")
}

dependencies {
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings(files(generatedMappingsJar.toFile()))
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")
    modCompileOnly("com.terraformersmc:modmenu:${project.property("modmenu_version")}")
    modLocalRuntime("com.terraformersmc:modmenu:${project.property("modmenu_version")}")
    modCompileOnly("me.shedaniel.cloth:cloth-config-fabric:${project.property("cloth_config_version")}")

    val localClothConfigJar = file(hmclClothConfigJar.get())
    if (localClothConfigJar.exists()) {
        modLocalRuntime(files(localClothConfigJar))
    } else {
        logger.warn("HMCL cloth config jar not found: {}", localClothConfigJar)
    }

    val localBasicMathJar = file(hmclBasicMathJar.get())
    if (localBasicMathJar.exists()) {
        modLocalRuntime(files(localBasicMathJar))
    } else {
        logger.warn("basic-math jar not found: {}", localBasicMathJar)
    }
}

tasks.processResources {
    dependsOn(syncRustWorkerResources)
    val minecraftVersion = project.property("minecraft_version") as String
    val loaderVersion = project.property("loader_version") as String
    val kotlinLoaderVersion = project.property("kotlin_loader_version") as String

    inputs.property("version", project.version.toString())
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("loader_version", loaderVersion)
    filteringCharset = "UTF-8"
    from(rustWorkerResourceDir)

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version.toString(),
            "minecraft_version" to minecraftVersion,
            "loader_version" to loaderVersion,
            "kotlin_loader_version" to kotlinLoaderVersion
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    // ensure that the encoding is set to UTF-8, no matter what the system default is
    // this fixes some edge cases with special characters not displaying correctly
    // see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
    // If Javadoc is generated, this must be specified in that task too.
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

tasks.withType<JavaExec>().configureEach {
    dependsOn(patchOfficialRuntimeTweakers)
}

tasks.matching { it.name == "runClient" }
    .configureEach {
        val hostTask = hostRustBundleTarget?.let(buildRustWorkerTasks::get)
        if (hostTask != null) {
            dependsOn(hostTask)
        } else {
            dependsOn(buildRustWorkers)
        }
    }

tasks.matching { it.name == "assemble" || it.name == "build" }
    .configureEach {
        dependsOn(buildRustWorkers)
    }

// configure the maven publication
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }

    // See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
    repositories {
        // Add repositories to publish to here.
        // Notice: This block does NOT have the same function as the block in the top level.
        // The repositories here will be used for publishing your artifact, not for
        // retrieving dependencies.
    }
}
