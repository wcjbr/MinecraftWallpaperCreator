package arch.zero.minecraftwallpapercreater.client

import arch.zero.minecraftwallpapercreater.Minecraftwallpapercreater
import net.minecraft.client.Minecraft
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class RustWorkerResult(
    val playlistPath: Path,
    val loopInfoPath: Path,
    val scriptPath: Path,
    val scriptBatPath: Path?,
    val mkvPath: Path?,
    val mkvScriptPath: Path?,
    val mkvScriptBatPath: Path?,
    val transitionFrames: Int,
    val baseFrames: Int,
    val loopStartFrame: Int,
    val selectionScore: Double
)

object RustWallpaperWorker {
    fun run(client: Minecraft, sessionDir: Path, config: WallpaperConfig): RustWorkerResult {
        val process = startProcess(client)
        val request = buildString {
            appendLine("session_dir=${sessionDir.toAbsolutePath()}")
            appendLine("target_fps=${config.targetFps}")
            appendLine("playback_fps=${config.playbackFps}")
            appendLine("export_format=${config.exportFormat.name}")
            appendLine("rife_threads=${config.rifeThreads}")
            appendLine("max_memory_mb=${config.maxMemoryMb}")
            appendLine("auto_blend_loop=${config.autoBlendLoop}")
            appendLine("blend_frame_count=${config.blendFrameCount}")
        }

        BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)).use { writer ->
            writer.write(request)
            writer.flush()
        }

        var result: RustWorkerResult? = null
        BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
            lines.forEach { line ->
                when {
                    line.startsWith("progress ") -> Minecraftwallpapercreater.LOGGER.info("[rust-worker] {}", line)
                    line.startsWith("done ") -> {
                        result = parseDoneLine(line)
                        Minecraftwallpapercreater.LOGGER.info("[rust-worker] {}", line)
                    }
                    line.startsWith("error ") -> throw IllegalStateException(line.removePrefix("error ").trim())
                    line.isNotBlank() -> Minecraftwallpapercreater.LOGGER.info("[rust-worker] {}", line)
                }
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("Rust worker exited with code $exitCode")
        }

        return result ?: throw IllegalStateException("Rust worker finished without a done line")
    }

    private fun parseDoneLine(line: String): RustWorkerResult {
        val fields = line.removePrefix("done ").trim().split(' ')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                part.substring(0, idx) to part.substring(idx + 1)
            }
            .toMap()

        return RustWorkerResult(
            playlistPath = Paths.get(fields.getValue("playlist")),
            loopInfoPath = Paths.get(fields.getValue("loop_info")),
            scriptPath = Paths.get(fields.getValue("script")),
            scriptBatPath = fields["script_bat"]?.takeIf { it.isNotBlank() }?.let(Paths::get),
            mkvPath = fields["mkv"]?.takeIf { it.isNotBlank() }?.let(Paths::get),
            mkvScriptPath = fields["mkv_script"]?.takeIf { it.isNotBlank() }?.let(Paths::get),
            mkvScriptBatPath = fields["mkv_script_bat"]?.takeIf { it.isNotBlank() }?.let(Paths::get),
            transitionFrames = fields.getValue("transition_frames").toInt(),
            baseFrames = fields.getValue("base_frames").toInt(),
            loopStartFrame = fields.getValue("loop_start_frame").toInt(),
            selectionScore = fields.getValue("selection_score").toDouble()
        )
    }

    private fun startProcess(client: Minecraft): Process {
        val projectRoot = locateProjectRoot(client)
        val releaseBinary = candidateBinary(projectRoot, "rust/target/release/wallpaper-worker")
        val debugBinary = candidateBinary(projectRoot, "rust/target/debug/wallpaper-worker")
        val manifestPath = projectRoot?.resolve("rust/Cargo.toml")
        val useNice = System.getProperty("os.name").lowercase().contains("linux")

        val workerCommand = when {
            releaseBinary != null -> listOf(releaseBinary.toString())
            debugBinary != null -> listOf(debugBinary.toString())
            manifestPath != null && Files.exists(manifestPath) -> listOf(
                "cargo", "run", "--release",
                "--manifest-path", manifestPath.toString(),
                "-p", "wallpaper-worker",
                "--"
            )
            else -> listOf(
                "cargo", "run", "--release",
                "--manifest-path", "rust/Cargo.toml",
                "-p", "wallpaper-worker",
                "--"
            )
        }
        val command = if (useNice) {
            listOf("nice", "-n", "10") + workerCommand
        } else {
            workerCommand
        }

        Minecraftwallpapercreater.LOGGER.info(
            "[rust-worker] projectRoot={} command={}",
            projectRoot?.toString() ?: "<not-found>",
            command.joinToString(" ")
        )

        return ProcessBuilder(command)
            .directory((projectRoot ?: Paths.get("").toAbsolutePath()).toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
    }

    private fun candidateBinary(projectRoot: Path?, relative: String): Path? {
        val path = (projectRoot?.resolve(relative) ?: Paths.get(relative)).toAbsolutePath()
        return if (Files.exists(path)) path else null
    }

    private fun locateProjectRoot(client: Minecraft): Path? {
        val envRoot = System.getenv("MWC_PROJECT_ROOT")?.takeIf { it.isNotBlank() }?.let(Paths::get)
        val codeSourceRoot = runCatching {
            Paths.get(
                RustWallpaperWorker::class.java.protectionDomain.codeSource.location.toURI()
            )
        }.getOrNull()

        val anchors = listOfNotNull(
            envRoot,
            client.gameDirectory.toPath().toAbsolutePath(),
            Paths.get("").toAbsolutePath(),
            codeSourceRoot?.toAbsolutePath()
        )

        for (anchor in anchors) {
            var current: Path? = if (Files.isDirectory(anchor)) anchor else anchor.parent
            while (current != null) {
                if (Files.exists(current.resolve("rust/Cargo.toml"))) {
                    return current
                }
                current = current.parent
            }
        }

        return null
    }
}
