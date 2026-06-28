package arch.zero.minecraftwallpapercreater.client

import me.shedaniel.clothconfig2.api.ConfigBuilder
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

object WallpaperConfigScreen {
    fun create(parent: Screen?): Screen {
        val client = Minecraft.getInstance()
        val current = WallpaperExporter.defaultConfig(client)
        val edited = EditedWallpaperConfig(current)

        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(ClientText.tr("config.minecraftwallpapercreater.title"))
            .setSavingRunnable {
                val saved = WallpaperExporter.saveConfig(client, edited.toConfig())
                edited.applyFrom(saved)
            }
            .setDoesConfirmSave(false)
            .setTransparentBackground(true)

        val entries = builder.entryBuilder()
        val category = builder.getOrCreateCategory(ClientText.tr("config.minecraftwallpapercreater.category.capture"))

        category.addEntry(
            entries.startIntField(ClientText.tr("config.minecraftwallpapercreater.frame_count"), edited.frameCount)
                .setDefaultValue(WallpaperConfig().frameCount)
                .setMin(1)
                .setMax(10_000)
                .setTooltip(ClientText.tr("config.minecraftwallpapercreater.frame_count.tooltip"))
                .setSaveConsumer { edited.frameCount = it }
                .build()
        )
        category.addEntry(
            entries.startTextDescription(
                ClientText.tr("config.minecraftwallpapercreater.capture_mode")
            ).build()
        )
        category.addEntry(
            entries.startIntField(ClientText.tr("config.minecraftwallpapercreater.capture_fps"), edited.targetFps)
                .setDefaultValue(WallpaperConfig().targetFps)
                .setMin(1)
                .setMax(360)
                .setTooltip(ClientText.tr("config.minecraftwallpapercreater.capture_fps.tooltip"))
                .setSaveConsumer { edited.targetFps = it }
                .build()
        )
        category.addEntry(
            entries.startBooleanToggle(
                ClientText.tr("config.minecraftwallpapercreater.hide_hud"),
                edited.hideHudWhileCapturing
            )
                .setDefaultValue(WallpaperConfig().hideHudWhileCapturing)
                .setTooltip(ClientText.tr("config.minecraftwallpapercreater.hide_hud.tooltip"))
                .setSaveConsumer { edited.hideHudWhileCapturing = it }
                .build()
        )
        category.addEntry(
            entries.startBooleanToggle(
                ClientText.tr("config.minecraftwallpapercreater.auto_blend_loop"),
                edited.autoBlendLoop
            )
                .setDefaultValue(WallpaperConfig().autoBlendLoop)
                .setTooltip(ClientText.tr("config.minecraftwallpapercreater.auto_blend_loop.tooltip"))
                .setSaveConsumer { edited.autoBlendLoop = it }
                .build()
        )
        category.addEntry(
            entries.startIntField(ClientText.tr("config.minecraftwallpapercreater.blend_frames"), edited.blendFrameCount)
                .setDefaultValue(WallpaperConfig().blendFrameCount)
                .setMin(0)
                .setMax(1000)
                .setTooltip(ClientText.tr("config.minecraftwallpapercreater.blend_frames.tooltip"))
                .setSaveConsumer { edited.blendFrameCount = it }
                .build()
        )
        category.addEntry(
            entries.startIntField(ClientText.tr("config.minecraftwallpapercreater.playback_fps"), edited.playbackFps)
                .setDefaultValue(WallpaperConfig().playbackFps)
                .setMin(24)
                .setMax(360)
                .setTooltip(ClientText.tr("config.minecraftwallpapercreater.playback_fps.tooltip"))
                .setSaveConsumer { edited.playbackFps = it }
                .build()
        )
        category.addEntry(
            entries.startEnumSelector(
                ClientText.tr("config.minecraftwallpapercreater.export_format"),
                ExportFormat::class.java,
                edited.exportFormat
            )
                .setDefaultValue(WallpaperConfig().exportFormat)
                .setEnumNameProvider { value ->
                    ClientText.tr("config.minecraftwallpapercreater.export_format.${value.name.lowercase()}")
                }
                .setTooltip(ClientText.tr("config.minecraftwallpapercreater.export_format.tooltip"))
                .setSaveConsumer { edited.exportFormat = it }
                .build()
        )
        category.addEntry(
            entries.startIntField(ClientText.tr("config.minecraftwallpapercreater.rife_threads"), edited.rifeThreads)
                .setDefaultValue(WallpaperConfig().rifeThreads)
                .setMin(0)
                .setMax(256)
                .setTooltip(ClientText.tr("config.minecraftwallpapercreater.rife_threads.tooltip"))
                .setSaveConsumer { edited.rifeThreads = it }
                .build()
        )
        category.addEntry(
            entries.startIntField(ClientText.tr("config.minecraftwallpapercreater.max_memory_mb"), edited.maxMemoryMb)
                .setDefaultValue(WallpaperConfig().maxMemoryMb)
                .setMin(256)
                .setMax(131_072)
                .setTooltip(ClientText.tr("config.minecraftwallpapercreater.max_memory_mb.tooltip"))
                .setSaveConsumer { edited.maxMemoryMb = it }
                .build()
        )
        category.addEntry(
            entries.startTextDescription(
                ClientText.tr("config.minecraftwallpapercreater.hint")
            ).build()
        )

        builder.setFallbackCategory(category)
        return builder.build()
    }

    private class EditedWallpaperConfig(config: WallpaperConfig) {
        var frameCount: Int = config.frameCount
        var targetFps: Int = config.targetFps
        var hideHudWhileCapturing: Boolean = config.hideHudWhileCapturing
        var autoBlendLoop: Boolean = config.autoBlendLoop
        var blendFrameCount: Int = config.blendFrameCount
        var playbackFps: Int = config.playbackFps
        var exportFormat: ExportFormat = config.exportFormat
        var rifeThreads: Int = config.rifeThreads
        var maxMemoryMb: Int = config.maxMemoryMb

        fun toConfig(): WallpaperConfig = WallpaperConfig(
            frameCount = frameCount,
            targetFps = targetFps,
            hideHudWhileCapturing = hideHudWhileCapturing,
            autoBlendLoop = autoBlendLoop,
            playbackFps = playbackFps,
            exportFormat = exportFormat,
            rifeThreads = rifeThreads,
            maxMemoryMb = maxMemoryMb,
            blendFrameCount = blendFrameCount
        )

        fun applyFrom(config: WallpaperConfig) {
            frameCount = config.frameCount
            targetFps = config.targetFps
            hideHudWhileCapturing = config.hideHudWhileCapturing
            autoBlendLoop = config.autoBlendLoop
            playbackFps = config.playbackFps
            exportFormat = config.exportFormat
            rifeThreads = config.rifeThreads
            maxMemoryMb = config.maxMemoryMb
            blendFrameCount = config.blendFrameCount
        }
    }
}
