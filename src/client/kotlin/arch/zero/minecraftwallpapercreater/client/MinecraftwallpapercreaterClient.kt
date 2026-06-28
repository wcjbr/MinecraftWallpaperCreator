package arch.zero.minecraftwallpapercreater.client

import arch.zero.minecraftwallpapercreater.MinecraftWallpaperCreaterShared
import arch.zero.minecraftwallpapercreater.Minecraftwallpapercreater
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.api.ClientModInitializer
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

class MinecraftwallpapercreaterClient : ClientModInitializer {
    private val captureHudElementId = Identifier.fromNamespaceAndPath(MinecraftWallpaperCreaterShared.MOD_ID, "capture_hook")
    private lateinit var captureKeyBinding: KeyMapping
    private lateinit var configKeyBinding: KeyMapping
    private var keyMappingsRegistered = false

    override fun onInitializeClient() {
        val category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("minecraftwallpapercreater", "main")
        )

        captureKeyBinding = KeyMapping(
            "key.minecraftwallpapercreater.capture",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            category
        )

        configKeyBinding = KeyMapping(
            "key.minecraftwallpapercreater.config",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F9,
            category
        )

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            if (!keyMappingsRegistered) {
                val options = client.options
                if (options != null) {
                    registerKeyMapping(options, captureKeyBinding)
                    registerKeyMapping(options, configKeyBinding)
                    keyMappingsRegistered = true
                }
            }

            while (captureKeyBinding.consumeClick()) {
                WallpaperExporter.startCapture(client)
            }
            while (configKeyBinding.consumeClick()) {
                if (hasClothConfig()) {
                    client.setScreen(WallpaperConfigScreen.create(client.screen))
                } else {
                    client.player?.sendSystemMessage(
                        ClientText.tr("message.minecraftwallpapercreater.capture.config_unavailable")
                    )
                }
            }
            WallpaperExporter.tick(client)
        })

        LevelRenderEvents.END_MAIN.register(LevelRenderEvents.EndMain {
            val client = Minecraft.getInstance()
            WallpaperExporter.render(client)
        })

        Minecraftwallpapercreater.LOGGER.info(
            "Client initializer active. Renderer stack={}",
            WallpaperExporter.rendererSummary()
        )
    }

    private fun registerKeyMapping(options: Options, keyMapping: KeyMapping) {
        val field = Options::class.java.getDeclaredField("keyMappings")
        field.isAccessible = true
        val current = field.get(options) as Array<KeyMapping>
        if (current.any { it.name == keyMapping.name }) {
            return
        }
        field.set(options, current + keyMapping)
        KeyMapping.resetMapping()
        options.save()
    }

    private fun hasClothConfig(): Boolean = try {
        Class.forName("me.shedaniel.clothconfig2.api.ConfigBuilder", false, javaClass.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    }
}
