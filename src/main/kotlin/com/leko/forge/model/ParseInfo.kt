package com.leko.forge.model

import com.leko.forge.types.Type

/**
 * What a piece of a parser rule (terminal, nonterminal, group, `?`, `*`, ...) produces.
 *
 * - The *value* of an element is what a label on it binds: the matched text for a
 *   terminal, the result of the rule for a nonterminal, `T?` for `x?`, `List<T>` for
 *   `x*` / `x+`. The value of a sequence is the value of its only element that is not
 *   an unlabeled terminal, or the concatenated text if all elements are terminals.
 *   If the values of all value producing elements are `T` or `List<T>` (at least one a
 *   list), the value of the sequence is their concatenation: `x ("," x)*` is a `List<X>`.
 *   The value of alternatives is the common super type of the alternatives' values.
 * - The *labels* of an element are all labels inside it. Seen from outside, a label
 *   inside `?` (or in only some alternatives) has an optional type, a label inside
 *   `*` / `+` a list type.
 */
class ParseInfo(
    val kind: Kind,
    /** null if the value can not be inferred, [reason] tells why */
    val type: Type?,
    val labels: LinkedHashMap<String, Type>,
    val reason: String? = null,
    /** for sequences: the value is the concatenation of the element values (`x ("," x)*` gives `List<X>`) */
    val flatten: Boolean = false,
) {
    enum class Kind {
        /** the element consists of terminals only, its value is the matched text */
        TEXT,
        VALUE,
    }
}
