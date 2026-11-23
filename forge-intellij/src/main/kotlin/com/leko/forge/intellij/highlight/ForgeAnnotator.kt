package com.leko.forge.intellij.highlight

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.leko.forge.intellij.parser.ForgeNameType

/** Colors the names by their role in the grammar: declarations, type references, calls, attributes, ... */
class ForgeAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val type = element.node.elementType as? ForgeNameType ?: return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element)
            .textAttributes(ForgeColors.of(type.role))
            .create()
    }
}
