package org.jetbrains.research.lumberjack

import astminer.common.model.Node
import astminer.common.model.ParseResult
import astminer.parse.antlr.SimpleNode
import astminer.parse.java.GumTreeJavaNode
import astminer.parse.java.GumTreeJavaParser
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import java.io.File

class Parser : CliktCommand() {

    private val directory: String by option(help = "Root directory with java files for parsing").required()
    private val numMerges: Int by option(help = "Number of merges to perform").int().default(100)

    private fun parseFiles() = GumTreeJavaParser().parseWithExtension(File(directory), "java")

    private fun transformTree(root: GumTreeJavaNode): SimpleNode {
        val simpleRoot = SimpleNode(root.getTypeLabel(), null, root.getToken())
        val simpleChildren = root.getChildren().map { transformTree(it as GumTreeJavaNode) }
        simpleRoot.setChildren(simpleChildren)
        return simpleRoot
    }

    private fun transformRoots(roots: List<ParseResult<GumTreeJavaNode>>): List<SimpleNode> = roots.mapNotNull {
        val root = it.root
        if (root != null) {
            val transformedRoot = transformTree(root)
            transformedRoot.setMetadata("filePath", it.filePath)
            transformedRoot
        } else {
            null
        }
    }

    private data class Edge(val upNode: Node, val bottomNode: Node)
    private fun Edge.getType() = "${this.upNode.getTypeLabel()} (${this.bottomNode.getTypeLabel()})"


    private val edgesByType = mutableMapOf<String, MutableList<Edge>>()
    private val edgeTypeCounts = mutableMapOf<String, Int>()
    private val allRoots = mutableSetOf<SimpleNode>()

    private fun checkTypePresence(node: Node, childType: String): Boolean {
        val typeSet = node.getMetadata("types") as MutableSet<String>?
        val present = typeSet != null && typeSet.contains(childType)
        val actualTypeSet = typeSet ?: mutableSetOf()
        actualTypeSet.add(childType)
        if (typeSet == null) {
            node.setMetadata("types", actualTypeSet)
        }
        return present
    }

    private fun canMerge(node: Node) = node.getTypeLabel() != "Block"

    private fun canMerge(edge: Edge) = canMerge(edge.bottomNode) && canMerge(edge.upNode)

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
        root.setMetadata("active", true)
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
        val active = node.getMetadata("active") as Boolean?
        return active != null && active
    }

    private fun prepareMergedNode(edge: Edge, edgeType: String): SimpleNode? {
        val (upNode, bottomNode) = edge

        if (!checkActive(upNode) || !checkActive(bottomNode)) {
            return null
        }
        upNode.setMetadata("active", false)
        bottomNode.setMetadata("active", false)

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
        mergedNode.setMetadata("active", true)
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
            mergedNode.setMetadata("filePath", upNode.getMetadata("filePath") as String)
            allRoots.add(mergedNode)
        }

        mergedNode.getChildren().forEach {
            addEdge(Edge(mergedNode, it))
        }
        return true
    }

    private fun getTreeSize(root: Node): Int = 1 + root.getChildren().map { getTreeSize(it) }.sum()

    override fun run() {
        val parsedFiles = parseFiles()
        println("Parsed ${parsedFiles.size} files")
        val roots = transformRoots(parsedFiles)
        println("Transformed the nodes")
        collectEdges(roots)

        allRoots.addAll(roots)

        val rootsWithSizes = allRoots.map { root ->
            Pair(root.getMetadata("filePath") as String, getTreeSize(root))
        }.toMap()

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

        allRoots.take(100).forEach {
            println("-------------------------------")
            println("path to file: ${it.getMetadata("filePath")}")
            it.prettyPrint(indentSymbol = "|   ")
            println("-------------------------------")
        }

        println(allRoots.map {
            val size = getTreeSize(it)
            val path = it.getMetadata("filePath") as String
            (rootsWithSizes[path] as Int).toFloat() / size
        }.sum() / allRoots.size)
    }
}

fun main(args: Array<String>) = Parser().main(args)
