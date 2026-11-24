package com.leko.forge.intellij.hierarchy

import ForgeLexer
import com.intellij.lang.refactoring.NamesValidator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.intellij.psi.ForgeDeclaredName

/**
 * Renames an attribute or a rule together with the declarations of its hierarchy: a syn definition with the
 * equations of the nodes below it (and the other way round), an abstract rule with its alternatives. They are one
 * attribute (rule) for the compiler, so renaming one of them alone breaks the program.
 */
class ForgeRenameProcessor : RenamePsiElementProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean = element is ForgeDeclaredName

    override fun prepareRenaming(element: PsiElement, newName: String, allRenames: MutableMap<PsiElement, String>) {
        for (related in hierarchyOf(element as ForgeDeclaredName)) {
            if (related !== element) allRenames[related] = newName
        }
    }

    companion object {
        /** All declarations connected to [start] through its hierarchy, transitively, [start] included. */
        fun hierarchyOf(start: ForgeDeclaredName): Set<ForgeDeclaredName> {
            if (start.role != ForgeNameRole.ATTRIBUTE && start.role != ForgeNameRole.PARSER_RULE) return setOf(start)
            val seen = linkedSetOf(start)
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val hierarchy = current.hierarchy ?: continue
                for (related in hierarchy.supers + hierarchy.subs) {
                    val element = related.element(current.containingFile) as? ForgeDeclaredName ?: continue
                    if (seen.add(element)) queue += element
                }
            }
            return seen
        }
    }
}

/** What a new name may be: an IDENT of ForgeLexer that is not a keyword. */
class ForgeNamesValidator : NamesValidator {
    private val keywords: Set<String> = (1..ForgeLexer.VOCABULARY.maxTokenType)
        .mapNotNull { ForgeLexer.VOCABULARY.getLiteralName(it)?.trim('\'') }
        .filter { it.isNotEmpty() && it.all(Char::isLetter) }
        .toSet() + "_"

    override fun isKeyword(name: String, project: Project?): Boolean = name in keywords

    override fun isIdentifier(name: String, project: Project?): Boolean =
        name.matches(Regex("[a-zA-Z_][a-zA-Z_0-9]*")) && !isKeyword(name, project)
}
