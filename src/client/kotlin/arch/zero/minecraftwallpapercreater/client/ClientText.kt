package arch.zero.minecraftwallpapercreater.client

import net.minecraft.network.chat.Component

object ClientText {
    fun tr(key: String, vararg args: Any): Component = Component.translatable(key, *args)
}
