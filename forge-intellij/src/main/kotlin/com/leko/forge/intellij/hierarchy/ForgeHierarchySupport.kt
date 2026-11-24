package com.leko.forge.intellij.hierarchy

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.ide.semantics.ForgeHierarchy
import com.leko.forge.ide.semantics.ForgeRelated
import com.leko.forge.intellij.lexer.ForgeTokenTypes
import com.leko.forge.intellij.psi.ForgeDeclaredName
import com.leko.forge.intellij.psi.ForgeNameElement
import com.leko.forge.intellij.psi.ForgeReference
import com.leko.forge.intellij.semantics.ForgeFileSemantics
import com.leko.forge.intellij.semantics.ForgeImports

/** The hierarchy of the declaration [element] names, if it has one. */
val ForgeNameElement.hierarchy: ForgeHierarchy?
    get() = ForgeFileSemantics.of(containingFile).hierarchy[textRange.startOffset]

/** The name element of a related declaration, in its file. */
fun ForgeRelated.element(context: PsiFile): PsiElement? {
    val file = ForgeImports.fileOf(resolution.source, context) ?: return null
    return ForgeReference.findDeclaration(file, resolution)
}

/**
 * The gutter icons of declarations with a hierarchy: implements / overrides (up), is implemented / is overridden
 * (down), as in Java and Kotlin; and of methods and properties that implement a member of a trait, for a type that
 * has all its members (arrows both ways).
 */
class ForgeLineMarkerProvider : RelatedItemLineMarkerProvider() {
    override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
        // markers belong to leaves: the IDENT of a name
        if (element.node.elementType != ForgeTokenTypes.IDENT) return
        val name = element.parent as? ForgeNameElement ?: return
        val file = name.containingFile
        val implementations = ForgeFileSemantics.of(file).traitImplementations[name.textRange.startOffset].orEmpty()
        if (implementations.isNotEmpty()) {
            val targets = implementations.mapNotNull { i ->
                ForgeImports.fileOf(i.member.source, file)?.let { ForgeReference.findDeclaration(it, i.member) }
            }
            result += NavigationGutterIconBuilder
                .create(com.leko.forge.intellij.ForgeIcons.TRAIT_IMPLEMENTATION)
                .setTargets(targets)
                .setTooltipText(implementations.joinToString("<br>", "<html>", "</html>") {
                    "Implements <b>${it.trait}.${it.member.name}</b>: ${it.type} is ${it.trait}"
                })
                .setPopupTitle("Implemented Trait Members")
                .createLineMarkerInfo(element)
        }
        val hierarchy = name.hierarchy ?: return
        if (hierarchy.supers.isNotEmpty()) {
            val implements = hierarchy.supers.all { it.abstract }
            result += NavigationGutterIconBuilder
                .create(if (implements) AllIcons.Gutter.ImplementingMethod else AllIcons.Gutter.OverridingMethod)
                .setTargets(hierarchy.supers.mapNotNull { it.element(file) })
                .setTooltipText((if (implements) "Implements " else "Overrides ") + hierarchy.supers.joinToString { it.label })
                .setPopupTitle(if (implements) "Implemented Declarations" else "Overridden Declarations")
                .createLineMarkerInfo(element)
        }
        if (hierarchy.subs.isNotEmpty()) {
            val implemented = hierarchy.abstract
            result += NavigationGutterIconBuilder
                .create(if (implemented) AllIcons.Gutter.ImplementedMethod else AllIcons.Gutter.OverridenMethod)
                .setTargets(hierarchy.subs.mapNotNull { it.element(file) })
                .setTooltipText((if (implemented) "Is implemented in " else "Is overridden in ") + hierarchy.subs.joinToString { it.label })
                .setPopupTitle(if (implemented) "Implementations" else "Overriding Declarations")
                .createLineMarkerInfo(element)
        }
    }
}

/**
 * Find Usages of an attribute or a rule with implementations (a syn definition and the syn equations of the
 * nodes below it, an abstract rule and its alternatives) also finds the implementations and their uses.
 */
class ForgeFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    private fun implementations(element: PsiElement): List<PsiElement> {
        val name = element as? ForgeNameElement ?: return emptyList()
        if (name.role != ForgeNameRole.ATTRIBUTE && name.role != ForgeNameRole.PARSER_RULE) return emptyList()
        val file = name.containingFile
        // an inh equation (`inh Add.depth { ... }`) uses the attribute already, it is not listed twice
        return name.hierarchy?.subs.orEmpty().mapNotNull { it.element(file) }.filterIsInstance<ForgeDeclaredName>()
    }

    override fun canFindUsages(element: PsiElement): Boolean = implementations(element).isNotEmpty()

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler? {
        if (forHighlightUsages) return null
        val implementations = implementations(element)
        return object : FindUsagesHandler(element) {
            override fun getPrimaryElements(): Array<PsiElement> = arrayOf(element) + implementations

            override fun processElementUsages(
                usageOf: PsiElement, processor: Processor<in UsageInfo>, options: FindUsagesOptions,
            ): Boolean {
                if (!super.processElementUsages(usageOf, processor, options)) return false
                if (usageOf !== element) return true
                // the implementations are usages of the definition
                return implementations.all { impl -> processor.process(ReadAction.computeBlocking<UsageInfo, RuntimeException> { UsageInfo(impl) }) }
            }
        }
    }
}
