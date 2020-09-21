package org.jetbrains.research.lumberjack

import astminer.common.model.Node
import astminer.common.model.ParseResult
import astminer.parse.antlr.SimpleNode

data class Edge(val upNode: Node, val bottomNode: Node) {
    fun getType() = "${this.upNode.getTypeLabel()} (${this.bottomNode.getTypeLabel()})"
}

fun getTreeSize(root: Node?): Int = root?.let { 1 + root.getChildren().map { getTreeSize(it) }.sum() } ?: 0

const val FILE_PATH_FIELD = "filePath"

fun toSimpleTree(root: Node): SimpleNode {
    val simpleRoot = SimpleNode(root.getTypeLabel(), null, root.getToken())
    val simpleChildren = root.getChildren().map { toSimpleTree(it) }
    simpleRoot.setChildren(simpleChildren)
    return simpleRoot
}

fun toSimpleTrees(roots: List<ParseResult<out Node>>): List<SimpleNode> = roots.mapNotNull { (root, filePath) ->
    root?.let {
        val transformedRoot = toSimpleTree(root)
        transformedRoot.setMetadata(FILE_PATH_FIELD, filePath)
        transformedRoot
    }
}
