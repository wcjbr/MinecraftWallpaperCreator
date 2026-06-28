package arch.zero.minecraftwallpapercreater.client

import arch.zero.minecraftwallpapercreater.Minecraftwallpapercreater
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

object WallpaperExporter {
    private var activeSession: WallpaperCaptureSession? = null

    fun startCapture(client: MinecraftClient) {
        startCapture(client, WallpaperConfigStore.get(client))
    }

    fun startCapture(client: MinecraftClient, frameCount: Int, frameIntervalTicks: Int, targetFps: Int) {
        startCapture(
            client,
            WallpaperConfig(
                frameCount = frameCount,
                frameIntervalTicks = frameIntervalTicks,
                targetFps = targetFps
            )
        )
    }

    fun startCapture(client: MinecraftClient, config: WallpaperConfig) {
        if (activeSession != null) {
            notify(client, "message.minecraftwallpapercreater.capture.already_running")
            return
        }

        if (client.world == null || client.player == null) {
            notify(client, "message.minecraftwallpapercreater.capture.world_required")
            return
        }

        val session = WallpaperCaptureSession(client, config.normalized())
        activeSession = session
        runCatching {
            session.start()
        }.onFailure { exception ->
            activeSession = null
            Minecraftwallpapercreater.LOGGER.error("Failed to start wallpaper capture", exception)
            notify(
                client,
                "message.minecraftwallpapercreater.capture.start_failed",
                exception.message ?: "unknown error"
            )
        }
    }

    fun tick(client: MinecraftClient) {
        val session = activeSession ?: return
        session.tick()
        if (session.isFinished()) {
            activeSession = null
        }
    }

    fun render(client: MinecraftClient) {
        val session = activeSession ?: return
        session.render()
        if (session.isFinished()) {
            activeSession = null
        }
    }

    fun cancel(client: MinecraftClient) {
        val session = activeSession ?: run {
            notify(client, "message.minecraftwallpapercreater.capture.no_active")
            return
        }

        session.cancel(ClientText.tr("message.minecraftwallpapercreater.capture.canceled"))
        activeSession = null
    }

    fun isCapturing(): Boolean = activeSession != null

    fun defaultConfig(client: MinecraftClient): WallpaperConfig = WallpaperConfigStore.get(client)

    fun saveConfig(client: MinecraftClient, config: WallpaperConfig): WallpaperConfig {
        return WallpaperConfigStore.save(client, config)
    }

    fun rendererSummary(): String {
        val loader = Thread.currentThread().contextClassLoader
        val parts = mutableListOf("vanilla")
        if (hasClass("net.caffeinemc.mods.sodium.client.SodiumClientMod", loader)) {
            parts += "sodium"
        }
        if (hasClass("net.irisshaders.iris.Iris", loader)) {
            parts += "iris"
        }
        return parts.joinToString("+")
    }

    private fun hasClass(name: String, classLoader: ClassLoader): Boolean = try {
        Class.forName(name, false, classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    private fun notify(client: MinecraftClient, key: String, vararg args: Any) {
        val text = ClientText.tr(key, *args)
        Minecraftwallpapercreater.LOGGER.info(text.string)
        client.player?.sendMessage(text, false)
    }
}
