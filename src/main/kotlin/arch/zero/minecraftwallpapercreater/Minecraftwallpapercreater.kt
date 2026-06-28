package arch.zero.minecraftwallpapercreater

import net.fabricmc.api.ModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class Minecraftwallpapercreater : ModInitializer {
    override fun onInitialize() {
        LOGGER.info("{} initialized in common environment", MinecraftWallpaperCreaterShared.MOD_ID)
    }

    companion object {
        val LOGGER: Logger = LoggerFactory.getLogger(MinecraftWallpaperCreaterShared.MOD_ID)
    }
}
