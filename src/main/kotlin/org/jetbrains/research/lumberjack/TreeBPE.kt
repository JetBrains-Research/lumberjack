package org.jetbrains.research.lumberjack

class TreeBPE<Label>(private val numMerges: Int) {

    private val edgesByType = mutableMapOf<String, MutableList<Edge>>()
    private val edgeTypeCounts = mutableMapOf<String, Int>()
    private val mergingSequence = mutableListOf<String>()

    private val allRoots = mutableSetOf<LightNode>()
    private val rootLabels = mutableMapOf<LightNode, Label?>()

    private fun prepareFitAndTransform(roots: List<LightNode>, labels: List<Label?>) {
        prepareTransform(roots, labels)
        mergingSequence.clear()
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
    }

    private fun anyMarked(node: LightNode, childType: String) = node.children.any {
        it.nodeType == childType && it.marked
    }

    private fun markChildren(node: LightNode, childType: String) = node.children.forEach {
        if (it.nodeType == childType) {
            it.marked = true
        }
    }

    private fun unmarkChildren(node: LightNode) {
        node.children.forEach {
            it.marked = false
        }
    }

    private fun addEdge(edge: Edge) {
        if (!edge.canMerge()) {
            return
        }
        val edgeType = edge.getType()
        val edgesList = edgesByType.getOrPut(edgeType) { mutableListOf() }
        edgesList.add(edge)
        if (!anyMarked(edge.upNode, edge.bottomNode.nodeType)) {
            markChildren(edge.upNode, edge.bottomNode.nodeType)
            edgeTypeCounts[edgeType] = edgeTypeCounts.getOrDefault(edgeType, 0) + 1
        } else {
            edge.bottomNode.marked = true
        }
    }

    private fun removeEdge(edge: Edge) {
        if (!edge.canMerge()) {
            return
        }
        val edgeType = edge.getType()
        edgeTypeCounts[edgeType] = edgeTypeCounts.getOrDefault(edgeType, 0) - 1
    }

    private fun collectEdges(root: LightNode) {
        root.children.forEach {
            addEdge(Edge(root, it))
            collectEdges(it)
        }
    }

    private fun collectEdges(roots: List<LightNode>) {
        roots.forEach {
            collectEdges(it)
        }
    }

    private fun prepareMergedNode(edge: Edge, edgeType: String): LightNode? {
        val (upNode, bottomNode) = edge

        if (upNode.hasMerged || bottomNode.hasMerged) {
            return null
        }
        upNode.hasMerged = true
        bottomNode.hasMerged = true

        val upToken = upNode.token
        val bottomToken = bottomNode.token
        val mergedToken = when {
            upToken.isEmpty() -> {
                bottomToken
            }
            bottomToken.isEmpty() -> {
                upToken
            }
            else -> {
                "${upToken}_$bottomToken"
            }
        }
        val mergedNode = LightNode(mergedToken, edgeType, upNode.parent, canMerge = true)
        upNode.children.forEach { upChild ->
            removeEdge(Edge(upNode, upChild))
            if (upChild == bottomNode) {
                mergedNode.addChildren(bottomNode.children)
                bottomNode.children.forEach { bottomChild ->
                    removeEdge(Edge(bottomNode, bottomChild))
                    bottomChild.marked = false
                }
            } else {
                mergedNode.addChild(upChild)
                upChild.marked = false
            }
        }
        return mergedNode
    }

    private fun merge(edge: Edge, edgeType: String): Boolean {
        val mergedNode = prepareMergedNode(edge, edgeType) ?: return false

        val (upNode, bottomNode) = edge
        val parent = upNode.parent

        if (parent != null) {
            removeEdge(Edge(parent, upNode))
            addEdge(Edge(parent, mergedNode))
            val children = parent.children
            val updatedParentChildren = children.map {
                if (it == upNode) {
                    mergedNode
                } else {
                    it
                }
            }.toList()
            parent.children.clear()
            parent.addChildren(updatedParentChildren)
        } else {
            allRoots.add(mergedNode)
            rootLabels[mergedNode] = rootLabels[upNode]
            allRoots.remove(upNode)
            rootLabels.remove(upNode)
        }
        mergedNode.children.forEach {
            addEdge(Edge(mergedNode, it))
        }
        return true
    }

    private fun mergeEdgeType(edgeType: String): Int {
        val edges = edgesByType[edgeType] ?: mutableListOf()
        var countMerges = 0
        edges.forEach {
            if (merge(it, edgeType)) {
                countMerges += 1
            }
        }
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
            println("Iteration $iter: merging $edgeType with count $count")
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