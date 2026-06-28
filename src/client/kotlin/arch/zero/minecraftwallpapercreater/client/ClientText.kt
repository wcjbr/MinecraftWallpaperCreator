package arch.zero.minecraftwallpapercreater.client

import net.minecraft.text.Text

object ClientText {
    fun tr(key: String, vararg args: Any): Text = Text.translatable(key, *args)
}
