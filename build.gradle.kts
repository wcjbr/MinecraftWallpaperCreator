import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
val rustWorkerReleaseBinary = rustWorkspaceDir.file("target/release/wallpaper-worker")
val generatedMappingsDir = layout.buildDirectory.dir("generated/mappings").get().asFile.toPath()
val generatedMappingsTiny = generatedMappingsDir.resolve("mappings.tiny")
val generatedMappingsJar = generatedMappingsDir.resolve("placeholder-mappings.jar")
val hmclClothConfigJar = providers.gradleProperty("hmcl_cloth_config_jar")
    .orElse("/home/archzero/.config/hmcl/.minecraft/versions/XPlus PerioTable based on Minecraft 26.1.2 (Fabric)/mods/cloth-config-26.1.154.jar")
val hmclBasicMathJar = providers.gradleProperty("hmcl_basic_math_jar")
    .orElse("/home/archzero/.gradle/caches/modules-2/files-2.1/me.shedaniel.cloth/basic-math/0.6.1/2ddd64b22126332794a5754ff9b942e17fb44c39/basic-math-0.6.1.jar")

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

val buildRustWorkerRelease by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Rust wallpaper worker in release mode."
    workingDir = layout.projectDirectory.asFile
    commandLine("cargo", "build", "--release", "--manifest-path", "rust/Cargo.toml", "-p", "wallpaper-worker")
    inputs.files(
        fileTree(rustWorkspaceDir) {
            include("**/*.rs")
            include("**/Cargo.toml")
            include("**/Cargo.lock")
        }
    )
    outputs.file(rustWorkerReleaseBinary)
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
    val minecraftVersion = project.property("minecraft_version") as String
    val loaderVersion = project.property("loader_version") as String
    val kotlinLoaderVersion = project.property("kotlin_loader_version") as String

    inputs.property("version", project.version.toString())
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("loader_version", loaderVersion)
    filteringCharset = "UTF-8"

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

tasks.matching { it.name == "runClient" || it.name == "assemble" || it.name == "build" }
    .configureEach {
        dependsOn(buildRustWorkerRelease)
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
