package arch.zero.minecraftwallpapercreater.client

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

class MinecraftWallpaperCreaterModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        return ConfigScreenFactory { parent -> WallpaperConfigScreen.create(parent) }
    }
}
