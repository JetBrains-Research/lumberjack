package org.jetbrains.research.lumberjack

import astminer.common.storage.RankedIncrementalIdStorage

class TreeBPE<Label>(private val numMerges: Int) {

    private val edgesByType = mutableMapOf<Long, MutableList<Edge>>()
    private val edgeTypeCounts = mutableMapOf<Long, Int>()
    private val mergingSequence = mutableListOf<Long>()
    private val upNodeQueue = mutableListOf<LightNode>()

    private val tokenStorage = RankedIncrementalIdStorage<String>()
    private val typeStorage = RankedIncrementalIdStorage<String>()
    private val nextType = RankedIncrementalIdStorage<Pair<Long, Long>>()

    private fun Edge.getType() = nextType.record(Pair(upNode.typeId, bottomNode.typeId))

    private val allRoots = mutableSetOf<LightNode>()
    private val rootLabels = mutableMapOf<LightNode, Label?>()

    private fun restoreType(typeId: Long): String {
        return if (typeId < 0) {
            typeStorage.lookUpValue(-typeId) ?: ""
        } else {
            val (upType, bottomType) = nextType.lookUpValue(typeId) ?: return ""
            return "${restoreType(upType)} (${restoreType(bottomType)})"
        }
    }

    private fun restoreToken(tokenIdList: List<Long>) = tokenIdList.joinToString(" | ") { tokenStorage.lookUpValue(it) ?: "" }

    private fun prepareFitAndTransform(roots: List<LightNode>, labels: List<Label?>) {
        prepareTransform(roots, labels)
        mergingSequence.clear()
    }

    private fun propagateTypesAndTokens(node: LightNode) {
        if (node.token.isNotEmpty()) {
            node.tokenIdList.add(tokenStorage.record(node.token))
        }
        node.typeId = -typeStorage.record(node.nodeType)

        node.children.forEach {
            propagateTypesAndTokens(it)
        }
    }

    private fun prepareTransform(roots: List<LightNode>, labels: List<Label?>) {
        edgesByType.clear()
        edgeTypeCounts.clear()
        allRoots.clear()
        allRoots.addAll(roots)
        rootLabels.clear()
        roots.zip(labels).forEach { (root, label) ->
            rootLabels[root] = label
        }
        roots.forEach {
            propagateTypesAndTokens(it)
        }
    }

    private fun addToNodeEdgeCount(upNode: LightNode, addition: Int, shouldAddEdges: Boolean) {
        if (!upNode.canMerge) {
            return
        }
        upNode.children.filter {
            it.canMerge
        }.groupBy {
            it.typeId
        }.forEach { (_, bottomNodes) ->
            val edgeType = Edge(upNode, bottomNodes[0]).getType()
            edgeTypeCounts[edgeType] = edgeTypeCounts.getOrDefault(edgeType, 0) + addition
            if (shouldAddEdges) {
                val edgesList = edgesByType.getOrPut(edgeType) { mutableListOf() }
                bottomNodes.forEach { bottomNode ->
                    edgesList.add(Edge(upNode, bottomNode))
                }
            }
        }
    }

    private fun addNodeEdges(upNode: LightNode) {
        addToNodeEdgeCount(upNode, 1, true)
    }

    private fun removeNodeEdges(upNode: LightNode) {
        addToNodeEdgeCount(upNode, -1, false)
    }

    private fun addSingleEdge(upNode: LightNode, bottomNode: LightNode) {
        addToNodeEdgeCount(upNode, 1, false)
        val edge = Edge(upNode, bottomNode)
        val edgesList = edgesByType.getOrPut(edge.getType()) { mutableListOf() }
        edgesList.add(edge)
    }

    private fun collectEdges(root: LightNode) {
        addNodeEdges(root)
        root.children.forEach {
            collectEdges(it)
        }
    }

    private fun collectEdges(roots: List<LightNode>) {
        roots.forEach {
            collectEdges(it)
        }
    }

    /**
     * Collapse the edge. The upper node will carry information from both nodes in order to reduce memory consumption.
     */
    private fun merge(edge: Edge, edgeType: Long): Boolean {
        val (upNode, bottomNode) = edge
        if (upNode.hasMerged || bottomNode.hasMerged || Edge(upNode, bottomNode).getType() != edgeType) {
            return false
        }
        upNode.hasMerged = true
        bottomNode.hasMerged = true
        upNodeQueue.add(upNode)

        val parent = upNode.parent

        // Remove old connections of the upNode
        if (parent != null) {
            removeNodeEdges(parent)
        }
        removeNodeEdges(upNode)
        removeNodeEdges(bottomNode)

        // Merge information from the bottomNode into the upNode
        upNode.typeId = edgeType
        upNode.tokenIdList.addAll(bottomNode.tokenIdList)

        val indexOfBottom = upNode.children.indexOf(bottomNode)
        val bottomChildren = bottomNode.children
        upNode.children.removeAt(indexOfBottom)
        upNode.children.addAll(indexOfBottom, bottomChildren)
        bottomChildren.forEach { child ->
            child.parent = upNode
        }

        // Restore connection with the updated upNode
        if (parent != null) {
            addSingleEdge(parent, upNode)
        }
        addNodeEdges(upNode)

        return true
    }

    private fun mergeEdgeType(edgeType: Long): Int {
        val edges = edgesByType[edgeType] ?: mutableListOf()
        var countMerges = 0
        edges.forEach {
            if (merge(it, edgeType)) {
                countMerges += 1
            }
        }
        upNodeQueue.forEach {
            it.hasMerged = false
        }
        upNodeQueue.clear()
        edgeTypeCounts.remove(edgeType)
        edgesByType.remove(edgeType)
        return countMerges
    }

    fun fitAndTransform(roots: List<LightNode>, labels: List<Label?> = emptyList()): List<Pair<LightNode, Label?>> {
        println("Fitting TreeBPE with $numMerges merges")

        prepareFitAndTransform(roots, labels)
        collectEdges(roots)

        repeat(numMerges) { iter ->
            val maxEntry = edgeTypeCounts.maxBy { (_, count) -> count }
            if (maxEntry == null) {
                println("Stopped at $iter because merged everything")
                return@repeat
            }
            val (edgeType, count) = maxEntry
            println("Iteration $iter: merging $edgeType (${nextType.lookUpValue(edgeType)}) with count $count")
            println("Actual type: ${restoreType(edgeType)}")
            mergingSequence.add(edgeType)
            val countMerges = mergeEdgeType(edgeType)
            println("Actually merged $countMerges")
        }
        return allRoots.map { node ->
            Pair(node, rootLabels[node])
        }
    }

    fun transform(roots: List<LightNode>, labels: List<Label?> = emptyList()): List<Pair<LightNode, Label?>> {
        println("Transforming TreeBPE with $numMerges merges")

        prepareTransform(roots, labels)
        collectEdges(roots)

        mergingSequence.forEachIndexed { iter, edgeType ->
            println("Iteration $iter of ${mergingSequence.size}: merging $edgeType")
            val countMerges = mergeEdgeType(edgeType)
            println("Merged $countMerges edges")
        }
        return allRoots.map { node ->
            Pair(node, rootLabels[node])
        }
    }
}