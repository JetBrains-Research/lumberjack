package org.jetbrains.research.lumberjack

import astminer.parse.java.GumTreeJavaParser
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.io.File

class Parser : CliktCommand() {

    private val directory: String by option(help="Root directory with java files for parsing").required()

    private fun parseFiles() = GumTreeJavaParser().parseWithExtension(File(directory), "java")

    override fun run() {
        val parsedFiles = parseFiles()
        println(parsedFiles.size)
        val parsedFile = parsedFiles[0]
        parsedFile.root?.prettyPrint(indentSymbol = "|  ")
        println(parsedFile.filePath)
    }
}

fun main(args: Array<String>) = Parser().main(args)