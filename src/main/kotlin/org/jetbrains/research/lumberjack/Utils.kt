package org.jetbrains.research.lumberjack

import astminer.common.model.Node

data class Edge(val upNode: LightNode, val bottomNode: LightNode)

fun getTreeSize(root: Node?): Int = root?.let { 1 + root.getChildren().map { getTreeSize(it) }.sum() } ?: 0

const val TOKEN_NODE_TYPE = "TOKEN_NODE"
const val DELIMITER = '^'

fun nodeToLightNode(root: Node, parent: LightNode? = null): LightNode? {
    if (root.getTypeLabel() == "Javadoc") {
        return null
    }
    val node = LightNode(root.getToken(), root.getTypeLabel(), parent)
    node.children.addAll(root.getChildren().mapNotNull { nodeToLightNode(it, node) })
    if (node.nodeType == "Block") {
        node.canMerge = false
    }
    return node
}

fun moveTokensToLeaves(root: LightNode) {
    root.children.forEach { moveTokensToLeaves(it) }
    if (root.token.isNotEmpty()) {
        val newChild = LightNode(root.token, TOKEN_NODE_TYPE, root)
        root.addChild(newChild)
    }
}

