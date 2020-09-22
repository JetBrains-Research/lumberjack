package org.jetbrains.research.lumberjack

import astminer.cli.LabeledParseResult
import astminer.cli.MethodNameExtractor
import astminer.cli.normalizeParseResult
import astminer.cli.processNodeToken
import astminer.common.*
import astminer.common.model.LabeledPathContexts
import astminer.common.model.Node
import astminer.parse.antlr.SimpleNode
import astminer.parse.java.GumTreeJavaParser
import astminer.paths.Code2VecPathStorage
import astminer.paths.PathMiner
import astminer.paths.PathRetrievalSettings
import astminer.paths.toPathContext
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import java.io.File

class Parser : CliktCommand() {

    private val inputDirectory: String by option(
        "--input",
        help = "Root directory with java files for parsing"
    ).required()

    private val outputDirectory: String by option(
        "--output",
        help = "Path to directory where the output will be stored"
    ).required()

    private val numMerges: Int by option(help = "Number of merges to perform").int().default(100)

    private val maxPathHeight: Int by option(
        "--maxH",
        help = "Maximum height of path for code2vec"
    ).int().default(8)

    private val maxPathWidth: Int by option(
        "--maxW",
        help = "Maximum width of path. " +
                "Note, that here width is the difference between token indices in contrast to the original code2vec."
    ).int().default(3)

    private val maxPathContexts: Int by option(
        "--maxContexts",
        help = "Number of path contexts to keep from each method."
    ).int().default(500)

    private val splitTokens: Boolean by option(
        "--split-tokens",
        help = "if passed, split tokens into sequence of tokens"
    ).flag(default = false)

    private fun parseFiles() = GumTreeJavaParser().parseWithExtension(File(inputDirectory), "java")

    private fun printCompressedTrees(compressedTrees: List<LabeledParseResult<SimpleNode>>) {
        compressedTrees.take(100).forEach { (root, label) ->
            println("-------------------------------")
            println("path to file: $label")
            root.prettyPrint(indentSymbol = "|   ")
            println("-------------------------------")
        }
    }

    private fun printCompressionInfo(
        compressedTrees: List<LabeledParseResult<SimpleNode>>,
        filesToSizes: Map<String, Int>
    ) {
        println(compressedTrees.map { (root, label) ->
            val size = getTreeSize(root)
            (filesToSizes[label] as Int).toFloat() / size
        }.sum() / compressedTrees.size)
    }

    private fun extractCode2VecData(compressedTrees: List<LabeledParseResult<SimpleNode>>) {
        val miner = PathMiner(PathRetrievalSettings(maxPathHeight, maxPathWidth))
        val storage = Code2VecPathStorage(outputDirectory)

        compressedTrees.forEach { (root, label) ->
            root.preOrder().forEach { node -> processNodeToken(node, splitTokens) }
            val paths = miner.retrievePaths(root).take(maxPathContexts)
            storage.store(LabeledPathContexts(label, paths.map {
                toPathContext(it) { node ->
                    node.getNormalizedToken()
                }
            }))
        }

        storage.close()
    }

    override fun run() {
        println("Parsing files")
        val parsedFiles = parseFiles()
        parsedFiles.forEach { normalizeParseResult(it, splitTokens) }
        println("Parsed ${parsedFiles.size} files")
        println("Splitting into labeled methods")
        val labelExtractor = MethodNameExtractor(hideMethodNames = true)
        val labeledMethods = parsedFiles.flatMap {
            labelExtractor.toLabeledData(it).map { (root, label) ->
                LabeledParseResult(root, splitToSubtokens(label).joinToString("|"))
            }
        }
        println("Parsed ${labeledMethods.size} methods")

        println("Transforming trees to SimpleNodes")
        val roots = toSimpleTrees(labeledMethods)
        println("Transformed the trees")

        val treeBPE = TreeBPE(numMerges, nodeFilters = listOf { node: Node -> node.getTypeLabel() != "Block" })
        val compressedTrees = treeBPE.fitAndTransform(roots)
        println("Compressed trees")

        println("Moving tokens to leaves")
        compressedTrees.forEach { moveTokensToLeaves(it.root) }
        println("Moved tokens to leaves")

        printCompressedTrees(compressedTrees)

        extractCode2VecData(compressedTrees)
    }
}

fun main(args: Array<String>) = Parser().main(args)
