package arch.zero.minecraftwallpapercreater.client

import arch.zero.minecraftwallpapercreater.Minecraftwallpapercreater
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.Framebuffer
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.util.ScreenshotRecorder
import net.minecraft.text.Text
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WallpaperCaptureSession(
    private val client: MinecraftClient,
    private val config: WallpaperConfig
) {
    private data class LoopPlan(
        val startIndex: Int,
        val endExclusive: Int,
        val transitionPaths: List<Path>,
        val selectionScore: Double
    ) {
        val baseFrameCount: Int
            get() = endExclusive - startIndex
    }

    private val captureIntervalNanos = (1_000_000_000.0 / config.targetFps).toLong().coerceAtLeast(1L)
    private val sessionId = SESSION_TIME_FORMAT.format(LocalDateTime.now())
    private val outputDir: Path = client.runDirectory.toPath()
        .resolve("wallpaper-exports")
        .resolve(sessionId)
    private val framesDir: Path = outputDir.resolve("frames")
    private val transitionsDir: Path = outputDir.resolve("loop-transitions")
    private val frameSignatureCache = mutableMapOf<Path, IntArray>()
    private val imageCache = mutableMapOf<Path, BufferedImage>()
    private val workerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "minecraftwallpapercreater-export").apply {
            isDaemon = true
        }
    }

    private var capturedFrames = 0
    @Volatile private var finished = false
    @Volatile private var completionQueued = false
    private var originalHudHidden = false
    private var hudStateCaptured = false
    private val framePaths = mutableListOf<Path>()
    private var transitionFrameCount = 0
    private var lastCaptureTimeNanos = Long.MIN_VALUE
    private var loopStartFrameIndex = 0
    private var loopEndFrameExclusive = 0
    private var loopSelectionScore = 0.0
    private var finalPlaylistPath: Path? = null
    private var finalLoopInfoPath: Path? = null
    private var finalScriptPath: Path? = null
    private var finalizeRequested = false
    private var writtenFrames = 0

    fun start() {
        Files.createDirectories(framesDir)
        if (config.autoBlendLoop && config.blendFrameCount > 0) {
            Files.createDirectories(transitionsDir)
        }
        captureHudState()
        lastCaptureTimeNanos = Long.MIN_VALUE
        writeManifest()
        postStatus(
            ClientText.tr(
                "message.minecraftwallpapercreater.capture.started",
                config.frameCount,
                config.targetFps,
                detectRendererStack()
            )
        )
    }

    fun tick() {
        if (finished) {
            return
        }

        if (completionQueued) {
            return
        }

        if (client.world == null) {
            finishEarly(ClientText.tr("message.minecraftwallpapercreater.capture.world_unloaded"))
            return
        }

    }

    fun render() {
        if (finished) {
            return
        }

        if (completionQueued) {
            return
        }

        if (client.world == null || client.player == null) {
            return
        }

        val now = System.nanoTime()
        if (lastCaptureTimeNanos != Long.MIN_VALUE && now - lastCaptureTimeNanos < captureIntervalNanos) {
            return
        }

        lastCaptureTimeNanos = now
        captureCurrentFrame(client.getFramebuffer())
        capturedFrames++

        if (capturedFrames >= config.frameCount) {
            completionQueued = true
            finalizeRequested = true
        }
    }

    fun isFinished(): Boolean = finished

    fun cancel(message: Text) {
        if (finished) {
            return
        }
        finished = true
        restoreHudState()
        postStatus(message)
        workerExecutor.shutdownNow()
    }

    private fun captureCurrentFrame(framebuffer: Framebuffer) {
        val frameIndex = capturedFrames
        val target = framesDir.resolve(frameFileName(frameIndex))
        ScreenshotRecorder.takeScreenshot(framebuffer) { image ->
            queueFrameWrite(frameIndex, image, target)
        }
    }

    private fun queueFrameWrite(frameIndex: Int, image: NativeImage, target: Path) {
        workerExecutor.execute {
            writeFrame(frameIndex, image, target)
        }
    }

    private fun writeFrame(frameIndex: Int, image: NativeImage, target: Path) {
        image.use {
            try {
                it.writeTo(target)
                framePaths.add(target)
                writtenFrames++
                logStatus(
                    ClientText.tr(
                        "message.minecraftwallpapercreater.capture.frame_saved",
                        frameIndex + 1,
                        config.frameCount,
                        target.fileName.toString()
                    )
                )
                if (finalizeRequested && !finished && writtenFrames >= config.frameCount) {
                    finalizeRequested = false
                    queueFinalizeExport()
                }
            } catch (exception: IOException) {
                Minecraftwallpapercreater.LOGGER.error("Failed to write wallpaper frame {}", target, exception)
                finished = true
                restoreHudState()
                postStatus(
                    ClientText.tr(
                        "message.minecraftwallpapercreater.capture.write_failed",
                        target.fileName.toString(),
                        exception.message ?: "unknown error"
                    )
                )
                workerExecutor.shutdownNow()
            }
        }
    }

    private fun queueFinalizeExport() {
        workerExecutor.execute {
            if (finished) {
                return@execute
            }
            if (writtenFrames < config.frameCount) {
                finalizeRequested = true
                return@execute
            }
            finalizeExport()
        }
    }

    private fun writeManifest() {
        val content = buildString {
            appendLine("mod=minecraftwallpapercreater")
            appendLine("session=$sessionId")
            appendLine("frame_count=${config.frameCount}")
            appendLine("capture_mode=render_frame")
            appendLine("capture_fps=${config.targetFps}")
            appendLine("target_fps=${config.targetFps}")
            appendLine("playback_fps=${config.playbackFps}")
            appendLine("export_format=${config.exportFormat.name}")
            appendLine("rife_threads=${config.rifeThreads}")
            appendLine("max_memory_mb=${config.maxMemoryMb}")
            appendLine("hide_hud_while_capturing=${config.hideHudWhileCapturing}")
            appendLine("auto_blend_loop=${config.autoBlendLoop}")
            appendLine("blend_frame_count=${config.blendFrameCount}")
            appendLine("captured_frames=${framePaths.size}")
            appendLine("loop_start_frame=$loopStartFrameIndex")
            appendLine("loop_end_frame_exclusive=$loopEndFrameExclusive")
            appendLine("loop_selection_score=${"%.6f".format(loopSelectionScore)}")
            appendLine("transition_frames=$transitionFrameCount")
            appendLine("loop_enabled=${config.autoBlendLoop && transitionFrameCount > 0}")
            appendLine("renderer_stack=${detectRendererStack()}")
            appendLine("world_loaded=${client.world != null}")
        }
        Files.writeString(outputDir.resolve("capture.properties"), content, StandardCharsets.UTF_8)
    }

    private fun finalizeExport() {
        try {
            val result = RustWallpaperWorker.run(client, outputDir, config.normalized())
            loopStartFrameIndex = result.loopStartFrame
            loopEndFrameExclusive = result.baseFrames
            loopSelectionScore = result.selectionScore
            transitionFrameCount = result.transitionFrames
            finalPlaylistPath = result.playlistPath
            finalLoopInfoPath = result.loopInfoPath
            finalScriptPath = result.scriptPath
            writeManifest()
            restoreHudState()
            postStatus(
                ClientText.tr(
                    "message.minecraftwallpapercreater.capture.ready",
                    outputDir.toAbsolutePath().toString()
                )
            )
        } catch (exception: Exception) {
            Minecraftwallpapercreater.LOGGER.error("Failed to finalize wallpaper export", exception)
            restoreHudState()
            postStatus(
                ClientText.tr(
                    "message.minecraftwallpapercreater.capture.finalize_failed",
                    exception.message ?: "unknown error"
                )
            )
        } finally {
            finished = true
            workerExecutor.shutdown()
        }
    }

    private fun captureHudState() {
        if (hudStateCaptured) {
            return
        }

        originalHudHidden = client.options.hudHidden
        hudStateCaptured = true
        if (config.hideHudWhileCapturing) {
            client.options.hudHidden = true
        }
    }

    private fun restoreHudState() {
        if (!hudStateCaptured) {
            return
        }

        client.options.hudHidden = originalHudHidden
        hudStateCaptured = false
    }

    private fun finishEarly(message: Text) {
        finished = true
        restoreHudState()
        postStatus(message)
    }

    private fun detectRendererStack(): String {
        val loader = Thread.currentThread().contextClassLoader
        val hasSodium = hasClass("net.caffeinemc.mods.sodium.client.SodiumClientMod", loader)
        val hasIris = hasClass("net.irisshaders.iris.Iris", loader)
        return buildList {
            add("vanilla")
            if (hasSodium) add("sodium")
            if (hasIris) add("iris")
        }.joinToString("+")
    }

    private fun hasClass(name: String, classLoader: ClassLoader): Boolean = try {
        Class.forName(name, false, classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    private fun postStatus(message: Text) {
        Minecraftwallpapercreater.LOGGER.info(message.string)
        client.execute {
            client.player?.sendMessage(message, false)
        }
    }

    private fun logStatus(message: Text) {
        Minecraftwallpapercreater.LOGGER.info(message.string)
    }

    private fun frameFileName(index: Int): String = "frame-${index.toString().padStart(5, '0')}.png"

    private fun transitionFileName(index: Int): String = "transition-${index.toString().padStart(5, '0')}.png"

    companion object {
        private val SESSION_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
