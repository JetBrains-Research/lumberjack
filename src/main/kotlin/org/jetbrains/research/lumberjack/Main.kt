package org.jetbrains.research.lumberjack

import astminer.common.model.Node
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

    override fun run() {
        println("Parsing files")
        val parsedFiles = parseFiles()
        val filesToSizes = parsedFiles.map { (root, filePath) ->
            Pair(filePath, getTreeSize(root))
        }.toMap()
        println("Parsed ${parsedFiles.size} files")

        println("Transforming trees to SimpleNodes")
        val roots = toSimpleTrees(parsedFiles)
        println("Transformed the nodes")

        val treeBPE = TreeBPE(numMerges, nodeFilters = listOf { node: Node -> node.getTypeLabel() != "Block" })
        val compressedTrees = treeBPE.transform(roots)
        println("Compressed trees")

        compressedTrees.take(100).forEach { (root, filePath) ->
            println("-------------------------------")
            println("path to file: ${filePath}")
            root?.prettyPrint(indentSymbol = "|   ")
            println("-------------------------------")
        }

        println(compressedTrees.map { (root, filePath) ->
            val size = getTreeSize(root)
            (filesToSizes[filePath] as Int).toFloat() / size
        }.sum() / compressedTrees.size)
    }
}

fun main(args: Array<String>) = Parser().main(args)
