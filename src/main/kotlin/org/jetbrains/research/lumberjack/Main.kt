package org.jetbrains.research.lumberjack

import astminer.cli.*
import astminer.common.*
import astminer.parse.java.GumTreeJavaParser
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import java.io.File

class Parser : CliktCommand() {

    private val inputDirectories: List<String> by option(
            "--input",
            help = "Root directory with java files for parsing"
    ).multiple(required = true)

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
    ).int().default(200)

    private val splitTokens: Boolean by option(
            "--split-tokens",
            help = "if passed, split tokens into sequence of tokens"
    ).flag(default = false)

    private fun parseFiles(folder: File, labelExtractor: LabelExtractor): List<Pair<LightNode, String>> {
        val files = folder.walk().toList().filter { it.extension == "java" }.toList() ?: return emptyList()
        val parsedMethods = mutableListOf<Pair<LightNode, String>>()
        GumTreeJavaParser().parseFiles(files) { parseResult ->
            normalizeParseResult(parseResult, splitTokens)
            labelExtractor.toLabeledData(parseResult).forEach { (root, label) ->
                val lightNode = nodeToLightNode(root) ?: return@forEach
                parsedMethods.add(Pair(lightNode, splitToSubtokens(label).joinToString("|")))
            }
        }
        return parsedMethods
    }

    private fun printCompressedTrees(compressedTrees: List<Pair<LightNode, String?>>) {
        compressedTrees.take(100).forEach { (root, label) ->
            println("-------------------------------")
            println("path to file: $label")
            root.prettyPrint(indentSymbol = "|   ")
            println("-------------------------------")
        }
    }

//    private fun printCompressionInfo(
//            compressedTrees: List<LabeledParseResult<SimpleNode>>,
//            filesToSizes: Map<String, Int>
//    ) {
//        println(compressedTrees.map { (root, label) ->
//            val size = getTreeSize(root)
//            (filesToSizes[label] as Int).toFloat() / size
//        }.sum() / compressedTrees.size)
//    }
//
//    private fun extractCode2VecData(
//            compressedTrees: List<LabeledParseResult<SimpleNode>>,
//            labelPrefix: String,
//            storage: Code2SeqPathStorage
//    ) {
//        val miner = PathMiner(PathRetrievalSettings(maxPathHeight, maxPathWidth))
//
//        compressedTrees.forEach { (root, label) ->
//            root.preOrder().forEach { node -> processNodeToken(node, splitTokens) }
//            val paths = miner.retrievePaths(root).take(maxPathContexts)
//            storage.store(LabeledPathContexts("$labelPrefix:$label", paths.map {
//                toPathContext(it) { node ->
//                    node.getNormalizedToken()
//                }
//            }))
//        }
//    }

    override fun run() {
        val treeBPE = TreeBPE<String>(numMerges)
        val storage = Code2SeqPathStorage(outputDirectory)
        inputDirectories.forEachIndexed { iter, folder ->
            println("Parsing files in $folder")
            val labelExtractor = MethodNameExtractor(hideMethodNames = true)
            val labeledMethods = parseFiles(File(folder), labelExtractor)
            println("Parsed ${labeledMethods.size} methods")

            val (methods, labels) = labeledMethods.unzip()
            val compressedTrees = if (iter == 0) {
                treeBPE.fitAndTransform(methods, labels)
            } else {
                treeBPE.transform(methods, labels)
            }
            println("Compressed trees")

            println("Moving tokens to leaves")
            compressedTrees.forEach { (root, _) ->
                moveTokensToLeaves(root)
            }
            println("Moved tokens to leaves")

            printCompressedTrees(compressedTrees)

//            extractCode2VecData(compressedTrees, folderName, storage)
        }
        storage.close()
    }
}

fun main(args: Array<String>) = Parser().main(args)
