package org.jetbrains.research.lumberjack

import astminer.cli.FilePathExtractor
import astminer.cli.MethodNameExtractor
import astminer.cli.normalizeParseResult
import astminer.common.getNormalizedToken
import astminer.common.model.LabeledPathContexts
import astminer.common.model.Node
import astminer.common.model.ParseResult
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

    private fun printCompressedTrees(compressedTrees: List<ParseResult<SimpleNode>>) {
        compressedTrees.take(100).forEach { (root, filePath) ->
            println("-------------------------------")
            println("path to file: ${filePath}")
            root?.prettyPrint(indentSymbol = "|   ")
            println("-------------------------------")
        }
    }

    private fun printCompressionInfo(
            compressedTrees: List<ParseResult<SimpleNode>>,
            filesToSizes: Map<String, Int>
    ) {
        println(compressedTrees.map { (root, filePath) ->
            val size = getTreeSize(root)
            (filesToSizes[filePath] as Int).toFloat() / size
        }.sum() / compressedTrees.size)
    }

    private fun extractCode2VecData(compressedTrees: List<ParseResult<SimpleNode>>) {
        val miner = PathMiner(PathRetrievalSettings(maxPathHeight, maxPathWidth))
        val storage = Code2VecPathStorage(outputDirectory)
        compressedTrees.forEach { normalizeParseResult(it, splitTokens) }

        val labelExtractor = FilePathExtractor()
        compressedTrees.forEach { parseResult ->
            val labeledParseResults = labelExtractor.toLabeledData(parseResult)
            labeledParseResults.forEach { (root, label) ->
                val paths = miner.retrievePaths(root).take(maxPathContexts)
                storage.store(LabeledPathContexts(label, paths.map {
                    toPathContext(it) { node ->
                        node.getNormalizedToken()
                    }
                }))
            }
        }

        storage.close()
    }

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

        printCompressedTrees(compressedTrees)
        printCompressionInfo(compressedTrees, filesToSizes)

        extractCode2VecData(compressedTrees)
    }
}

fun main(args: Array<String>) = Parser().main(args)
