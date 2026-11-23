package com.leko.forge.intellij

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object ForgeLanguage : Language("Forge") {
    private fun readResolve(): Any = ForgeLanguage
}

object ForgeIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/forge.svg", ForgeIcons::class.java)
}

object ForgeFileType : LanguageFileType(ForgeLanguage) {
    override fun getName(): String = "Forge"
    override fun getDescription(): String = "Forge compiler definition"
    override fun getDefaultExtension(): String = "forge"
    override fun getIcon(): Icon = ForgeIcons.FILE
}
