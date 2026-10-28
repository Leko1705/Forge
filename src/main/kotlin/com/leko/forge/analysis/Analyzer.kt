package com.leko.forge.analysis

import ForgeParser
import com.leko.forge.attr.Attr
import com.leko.forge.enter.Enter
import com.leko.forge.context.Context
import com.leko.forge.enter.MemberEnter
import com.leko.forge.util.Log

/**
 * Orchestrator — javac's `JavaCompiler#compile` analog. Drives the fixed phase
 * pipeline over one parsed root, stopping early if an earlier phase already
 * produced errors that would make later phases meaningless (e.g. no point
 * running completeness checking if node names themselves failed to resolve).
 */
class Analyzer private constructor(context: Context) {
    companion object {
        fun instance(context: Context): Analyzer =
            context.getServices(Analyzer::class) { Analyzer(context) }
    }

    private val enter = Enter.instance(context)

    private val member = MemberEnter.instance(context)

    private val dep = Dependency.instance(context)

    private val attr = Attr.instance(context)

    private val log = Log.instance(context)


    fun analyze(root: ForgeParser.RootContext) {
        enter.enter(root)
        member.enter(root)
        attr.attr(root)
        dep.check(root)
        log.report()
    }
}
