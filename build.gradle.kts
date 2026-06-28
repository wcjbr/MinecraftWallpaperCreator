import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.0"
    id("fabric-loom") version "1.17.12"
    id("maven-publish")
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

val hmclInstanceDir = providers.gradleProperty("hmcl_instance_dir").orNull
val localModMenuJar = hmclInstanceDir
    ?.let { file("$it/mods/${project.property("modmenu_jar_name")}") }
    ?.takeIf { it.exists() }
val localClothConfigJar = hmclInstanceDir
    ?.let { file("$it/mods/${project.property("cloth_config_jar_name")}") }
    ?.takeIf { it.exists() }
val localClothBasicMathJar = hmclInstanceDir
    ?.let { instanceDir ->
        fileTree("$instanceDir/.fabric/processedMods") {
            include("cloth-basic-math-*.jar")
            include("basic-math-*.jar")
        }.files.firstOrNull()
    }
    ?.takeIf { it.exists() }

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val rustWorkspaceDir = layout.projectDirectory.dir("rust")
val rustWorkerReleaseBinary = rustWorkspaceDir.file("target/release/wallpaper-worker")
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

val targetJavaVersion = 21
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
    // Add repositories to retrieve artifacts from in here.
    // You should only use this when depending on other mods because
    // Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
    // See https://docs.gradle.org/current/userguide/declaring_repositories.html
    // for more information about repositories.
}

dependencies {
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    if (localModMenuJar != null) {
        modCompileOnly(files(localModMenuJar))
        modLocalRuntime(files(localModMenuJar))
    }

    if (localClothConfigJar != null) {
        modCompileOnly(files(localClothConfigJar))
        modLocalRuntime(files(localClothConfigJar))
    }

    if (localClothBasicMathJar != null) {
        compileOnly(files(localClothBasicMathJar))
        runtimeOnly(files(localClothBasicMathJar))
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
