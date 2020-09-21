package org.jetbrains.research.lumberjack

import astminer.common.model.Node
import astminer.common.model.ParseResult
import astminer.parse.antlr.SimpleNode

class TreeBPE(
        private val numMerges: Int,
        private val nodeFilters: List<(Node) -> Boolean> = emptyList(),
        private val edgeFilters: List<(Edge) -> Boolean> = emptyList()
) {
    companion object {
        private const val ACTIVE_FIELD = "active"
        private const val TYPES_FIELD = "types"
    }

    private val edgesByType = mutableMapOf<String, MutableList<Edge>>()
    private val edgeTypeCounts = mutableMapOf<String, Int>()
    private val allRoots = mutableSetOf<SimpleNode>()

    private fun clearState() {
        edgesByType.clear()
        edgeTypeCounts.clear()
        allRoots.clear()
    }

    private fun checkTypePresence(node: Node, childType: String): Boolean {
        val typeSet = node.getMetadata(TYPES_FIELD) as MutableSet<String>?
        val present = typeSet != null && typeSet.contains(childType)
        val actualTypeSet = typeSet ?: mutableSetOf()
        actualTypeSet.add(childType)
        if (typeSet == null) {
            node.setMetadata(TYPES_FIELD, actualTypeSet)
        }
        return present
    }

    private fun canMerge(node: Node) = nodeFilters.all { it(node) }

    private fun canMerge(edge: Edge) =
            canMerge(edge.bottomNode) && canMerge(edge.upNode) && edgeFilters.all { it(edge) }

    private fun addEdge(edge: Edge) {
        if (!canMerge(edge)) {
            return
        }
        val edgeType = edge.getType()
        val edgesList = edgesByType.getOrPut(edgeType) { mutableListOf() }
        edgesList.add(edge)
        if (!checkTypePresence(edge.upNode, edge.bottomNode.getTypeLabel())) {
            edgeTypeCounts[edgeType] = edgeTypeCounts.getOrDefault(edgeType, 0) + 1
        }
    }

    private fun removeEdge(edge: Edge) {
        if (!canMerge(edge)) {
            return
        }
        val edgeType = edge.getType()
        edgeTypeCounts[edgeType] = edgeTypeCounts.getOrDefault(edgeType, 0) - 1
    }

    private fun collectEdges(root: Node) {
        root.setMetadata(ACTIVE_FIELD, true)
        root.getChildren().forEach {
            addEdge(Edge(root, it))
            collectEdges(it)
        }
    }

    private fun collectEdges(roots: List<Node>) {
        roots.forEach {
            collectEdges(it)
        }
    }

    private fun checkActive(node: Node): Boolean {
        val active = node.getMetadata(ACTIVE_FIELD) as Boolean?
        return active != null && active
    }

    private fun prepareMergedNode(edge: Edge, edgeType: String): SimpleNode? {
        val (upNode, bottomNode) = edge

        if (!checkActive(upNode) || !checkActive(bottomNode)) {
            return null
        }
        upNode.setMetadata(ACTIVE_FIELD, false)
        bottomNode.setMetadata(ACTIVE_FIELD, false)

        val upToken = upNode.getToken()
        val bottomToken = bottomNode.getToken()
        val mergedToken = when {
            upToken.isEmpty() -> {
                bottomToken
            }
            bottomToken.isEmpty() -> {
                upToken
            }
            else -> {
                "$upToken | $bottomToken"
            }
        }
        val mergedNode = SimpleNode(edgeType, upNode.getParent(), mergedToken)
        val mergedChildren = mutableListOf<Node>()
        upNode.getChildren().forEach { upChild ->
            removeEdge(Edge(upNode, upChild))
            if (upChild == bottomNode) {
                mergedChildren.addAll(bottomNode.getChildren())
                bottomNode.getChildren().forEach { bottomChild ->
                    removeEdge(Edge(bottomNode, bottomChild))
                }
            } else {
                mergedChildren.add(upChild)
            }
        }
        mergedNode.setChildren(mergedChildren)
        mergedNode.setMetadata(ACTIVE_FIELD, true)
        return mergedNode
    }

    private fun merge(edge: Edge, edgeType: String): Boolean {
        val mergedNode = prepareMergedNode(edge, edgeType) ?: return false

        val (upNode, _) = edge
        val parent = upNode.getParent() as SimpleNode?

        if (parent != null) {
            removeEdge(Edge(parent, upNode))
            addEdge(Edge(parent, mergedNode))
            val children = parent.getChildren()
            val updatedParentChildren = children.map {
                if (it == upNode) {
                    mergedNode
                } else {
                    it
                }
            }.toList()
            parent.setChildren(updatedParentChildren)
        } else {
            allRoots.remove(upNode)
            mergedNode.setMetadata(FILE_PATH_FIELD, upNode.getMetadata(FILE_PATH_FIELD) as String)
            allRoots.add(mergedNode)
        }

        mergedNode.getChildren().forEach {
            addEdge(Edge(mergedNode, it))
        }
        return true
    }

    fun transform(roots: List<SimpleNode>): List<ParseResult<SimpleNode>> {
        println("Running TreeBPE with $numMerges merges")

        clearState()

        collectEdges(roots)
        allRoots.addAll(roots)

        repeat(numMerges) { iter ->
            val maxEntry = edgeTypeCounts.maxBy { (_, count) -> count }
            if (maxEntry == null) {
                println("Stopped at $iter because merged everything")
                return@repeat
            }
            val (edgeType, count) = maxEntry
            println("Iteration $iter: merging $edgeType with count $count")
            val edges = edgesByType[edgeType] ?: mutableListOf()
            var countMerges = 0
            edges.forEach {
                if (merge(it, edgeType)) {
                    countMerges += 1
                }
            }
            edgeTypeCounts.remove(edgeType)
            edgesByType.remove(edgeType)
            println("Actually merged $countMerges")
        }

        return allRoots.map { node ->
            ParseResult(node, node.getMetadata(FILE_PATH_FIELD) as String)
        }
    }
}