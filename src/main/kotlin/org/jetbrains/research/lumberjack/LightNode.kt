package org.jetbrains.research.lumberjack

class LightNode(
        var token: String,
        var nodeType: String,
        var parent: LightNode?,
        val children: MutableList<LightNode> = mutableListOf(),
        var canMerge: Boolean = true,
        var marked: Boolean = false,
        var hasMerged: Boolean = false
) {
    fun addChild(node: LightNode) {
        children.add(node)
        node.parent = this
    }

    fun addChildren(nodes: List<LightNode>) {
        children.addAll(nodes)
        nodes.forEach {
            it.parent = this
        }
    }

    fun prettyPrint(indent: Int = 0, indentSymbol: String = "| ") {
        repeat(indent) { print(indentSymbol) }
        print(nodeType)
        if (token.isNotEmpty()) {
            println(" : $token")
        } else {
            println()
        }
        children.forEach { it.prettyPrint(indent + 1, indentSymbol) }
    }
}

