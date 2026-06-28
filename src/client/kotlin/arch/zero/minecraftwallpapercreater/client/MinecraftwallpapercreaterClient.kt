package arch.zero.minecraftwallpapercreater.client

import arch.zero.minecraftwallpapercreater.MinecraftWallpaperCreaterShared
import arch.zero.minecraftwallpapercreater.Minecraftwallpapercreater
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.api.ClientModInitializer
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

class MinecraftwallpapercreaterClient : ClientModInitializer {
    private val captureHudElementId = Identifier.of(MinecraftWallpaperCreaterShared.MOD_ID, "capture_hook")
    private lateinit var captureKeyBinding: KeyBinding
    private lateinit var configKeyBinding: KeyBinding

    override fun onInitializeClient() {
        val category = KeyBinding.Category.create(
            Identifier.of("minecraftwallpapercreater", "main")
        )

        captureKeyBinding = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.minecraftwallpapercreater.capture",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                category
            )
        )

        configKeyBinding = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.minecraftwallpapercreater.config",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                category
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            while (captureKeyBinding.wasPressed()) {
                WallpaperExporter.startCapture(client)
            }
            while (configKeyBinding.wasPressed()) {
                client.setScreen(WallpaperConfigScreen.create(client.currentScreen))
            }
            WallpaperExporter.tick(client)
        })

        HudElementRegistry.addLast(captureHudElementId) { _, _ ->
            val client = net.minecraft.client.MinecraftClient.getInstance()
            WallpaperExporter.render(client)
        }

        Minecraftwallpapercreater.LOGGER.info(
            "Client initializer active. Renderer stack={}, HMCL test instance={}",
            WallpaperExporter.rendererSummary(),
            HMCL_TEST_INSTANCE
        )
    }

    companion object {
        private const val HMCL_TEST_INSTANCE = "/home/archzero/.config/hmcl/.minecraft/versions/1.21.11-Fabric"
    }
}
