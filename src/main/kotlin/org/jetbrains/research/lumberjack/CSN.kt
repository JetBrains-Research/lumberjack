package org.jetbrains.research.lumberjack

import com.github.ajalt.clikt.core.CliktCommand
import com.google.gson.Gson
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.text.Charsets.UTF_8

private val filteringRegex = Regex("[_a-zA-Z]+")

data class JsonTree(val type: String, val string: String, val children: List<JsonTree>) {

    fun toLightTree(parent: LightNode? = null): LightNode? {
        if (shouldFilter(parent)) {
            return null
        }
        val currentNode = LightNode(string, type, parent, canMerge = type != "block")
        currentNode.children.addAll(children.mapNotNull { it.toLightTree(currentNode) })
        return currentNode
    }

    private fun shouldFilter(parent: LightNode?): Boolean {
        return !type.matches(filteringRegex) || (
                type == "expression_statement" &&
                        parent?.nodeType == "block" &&
                        parent.parent?.nodeType == "function_definition" &&
                        children.isNotEmpty() &&
                        children[0].type == "string"
                )
    }
}

fun readGzip(file: File): List<LightNode> {
    println("Reading ${file.name}")
    val gson = Gson()
    val trees = mutableListOf<LightNode>()
    GZIPInputStream(file.inputStream()).bufferedReader(UTF_8).forEachLine { line ->
        val tree = gson.fromJson(line, JsonTree::class.java)?.toLightTree()
        if (tree != null) {
            trees.add(tree)
        }
    }
    return trees
}

class CSN : CliktCommand() {
    override fun run() {
        val folder = File("/home/egor/work/semantic-code-search/resources/data/python/final/raw_trees/train/")
        val trees = folder.listFiles()?.take(5)?.flatMap {
            readGzip(it)
        } ?: emptyList()
        val treeBPE = TreeBPE<String>(200)
        val transformedTrees = treeBPE.fitAndTransform(trees)
        transformedTrees.take(10).forEach { (node, _) ->
            println()
            node.prettyPrint()
        }
    }
}

fun main(args: Array<String>) = CSN().main(args)
