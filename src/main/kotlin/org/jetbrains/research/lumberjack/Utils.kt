package org.jetbrains.research.lumberjack

import astminer.cli.LabeledParseResult
import astminer.common.getNormalizedToken
import astminer.common.model.Node
import astminer.common.model.ParseResult
import astminer.parse.antlr.SimpleNode

data class Edge(val upNode: Node, val bottomNode: Node) {
    fun getType() = "${this.upNode.getTypeLabel()} (${this.bottomNode.getTypeLabel()})"
}

fun getTreeSize(root: Node?): Int = root?.let { 1 + root.getChildren().map { getTreeSize(it) }.sum() } ?: 0

const val LABEL_FIELD = "label"
const val TOKEN_NODE_TYPE = "TOKEN_NODE"

fun toSimpleTree(root: Node): SimpleNode {
    val simpleRoot = SimpleNode(root.getTypeLabel(), null, root.getNormalizedToken().replace('|', '_'))
    val simpleChildren = root.getChildren().map { toSimpleTree(it) }
    simpleRoot.setChildren(simpleChildren)
    return simpleRoot
}

fun toSimpleTrees(roots: List<LabeledParseResult<out Node>>): List<SimpleNode> = roots.map { (root, label) ->
    root.let {
        val transformedRoot = toSimpleTree(root)
        transformedRoot.setMetadata(LABEL_FIELD, label)
        transformedRoot
    }
}

fun moveTokensToLeaves(root: SimpleNode) {
    root.getChildren().forEach { moveTokensToLeaves(it as SimpleNode) }
    if (root.getToken().isNotEmpty()) {
        val newChild = SimpleNode(TOKEN_NODE_TYPE, root, root.getToken())
        root.setChildren(listOf(newChild) + root.getChildren())
    }
}

