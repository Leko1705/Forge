package com.leko.forge.intellij.highlight

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.intellij.psi.ForgeNameElement

/**
 * Colors the names: by what the compiler resolved them to (so `this.type` is an attribute if `type` is a syn
 * attribute, but a field if it is a field of the node), or else by their role in the grammar.
 */
class ForgeAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is ForgeNameElement) return
        val resolution = element.resolution
        // a call of std's TODO has the color of TODO comments as a whole (ForgeCompilerAnnotator), its name too
        if (resolution != null && resolution.name == "TODO" && resolution.source == com.leko.forge.parse.STD_SOURCE) return
        val role = resolution?.role ?: element.role
        if (role == ForgeNameRole.IDENTIFIER) return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element)
            .textAttributes(ForgeColors.of(role))
            .create()
    }
}
