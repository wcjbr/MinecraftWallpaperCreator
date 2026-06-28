package arch.zero.minecraftwallpapercreater.client

import arch.zero.minecraftwallpapercreater.MinecraftWallpaperCreaterShared
import com.google.gson.GsonBuilder
import net.minecraft.client.MinecraftClient
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

enum class ExportFormat {
    PNG_SEQUENCE,
    MKV_H264,
    MKV_H265,
    MP4_H264,
    WEBM_VP9
}

data class WallpaperConfig(
    val frameCount: Int = 180,
    val frameIntervalTicks: Int = 1,
    val targetFps: Int = 24,
    val playbackFps: Int = 60,
    val exportFormat: ExportFormat = ExportFormat.MKV_H264,
    val rifeThreads: Int = 0,
    val maxMemoryMb: Int = 2048,
    val hideHudWhileCapturing: Boolean = true,
    val autoBlendLoop: Boolean = true,
    val blendFrameCount: Int = 24
) {
    fun normalized(): WallpaperConfig = copy(
        frameCount = frameCount.coerceAtLeast(1),
        frameIntervalTicks = frameIntervalTicks.coerceAtLeast(1),
        targetFps = targetFps.coerceIn(1, 360),
        playbackFps = playbackFps.coerceIn(targetFps.coerceAtLeast(1), 360),
        exportFormat = exportFormat,
        rifeThreads = rifeThreads.coerceIn(0, 256),
        maxMemoryMb = maxMemoryMb.coerceIn(256, 131_072),
        blendFrameCount = blendFrameCount.coerceAtLeast(0)
    )
}

object WallpaperConfigStore {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var cachedConfig: WallpaperConfig? = null

    fun get(client: MinecraftClient): WallpaperConfig {
        cachedConfig?.let { return it }

        val path = configPath(client)
        val loaded = runCatching {
            if (!Files.exists(path)) {
                WallpaperConfig()
            } else {
                Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                    gson.fromJson(reader, WallpaperConfig::class.java) ?: WallpaperConfig()
                }
            }
        }.getOrDefault(WallpaperConfig()).normalized()

        cachedConfig = loaded
        return loaded
    }

    fun save(client: MinecraftClient, config: WallpaperConfig): WallpaperConfig {
        val normalized = config.normalized()
        val path = configPath(client)
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
            gson.toJson(normalized, writer)
        }
        cachedConfig = normalized
        return normalized
    }

    private fun configPath(client: MinecraftClient): Path = client.runDirectory.toPath()
        .resolve("config")
        .resolve("${MinecraftWallpaperCreaterShared.MOD_ID}.json")
}
