// FindFile, a file search util for command line; see helpInfo below
// SPDX-FileCopyrightText: © 2022-2023 Oleksii Kovtun <alexvt.com>
// SPDX-License-Identifier: MIT
import kotlinx.cinterop.*
import platform.posix.*

private const val DEFAULT_DIR_ENV_VAR_NAME = "FF_DEFAULT_DIR"
private val defaultFolder =
    getenv(DEFAULT_DIR_ENV_VAR_NAME)?.toKString()?.takeIf { it.isNotBlank() }
        ?: "~"

private const val STYLE_NONE = "\u001B[0m"
private const val STYLE_BOLD = "\u001B[1m"
private const val STYLE_RED = "\u001B[31m"
private const val STYLE_YELLOW = "\u001B[33m"
private const val STYLE_BLUE = "\u001B[94m"
private const val STYLE_GRAY = "\u001B[37m"

private val helpInfo = """FindFile, a file search utility used similarly to online search engines.
    ${STYLE_GRAY}A find command wrapper with added highlights and formatting.${STYLE_NONE}
    Searches files containing given path parts, in $defaultFolder by default.
    Excludes results containing any of path parts with leading dashes.
    Shows results in a numbered list of full paths.
    Can open results by given numbers. ${STYLE_GRAY}Mind that results may change.
    * Asterisks around path parts are optional, are used automatically.
    Directories to search in, excluded parts and result numbers are optional.
    Default directory can be set in FF_DEFAULT_DIR environment variable.
    Use keys separately, like -p -i -n (-pin is excluded as a path part).${STYLE_NONE}
Usage:
    ff <include>
    ff <dir> <dir> <include> <include> -<exclude> -<exclude> - <result_number>
Sort ${STYLE_GRAY}keys (one at a time):${STYLE_NONE}
    -n ${STYLE_GRAY}(${STYLE_NONE}-N${STYLE_GRAY}):${STYLE_NONE} sort ${STYLE_GRAY}by${STYLE_NONE}  name           ${STYLE_GRAY}a to Z        (Z to a)${STYLE_NONE}
    -s ${STYLE_GRAY}(${STYLE_NONE}-S${STYLE_GRAY}):${STYLE_NONE} sort ${STYLE_GRAY}by${STYLE_NONE}  size           ${STYLE_GRAY}small to big  (big to small)${STYLE_NONE}
    -m ${STYLE_GRAY}(${STYLE_NONE}-M${STYLE_GRAY}):${STYLE_NONE} sort ${STYLE_GRAY}by${STYLE_NONE}  modified time  ${STYLE_GRAY}new to old    (old to new, default)${STYLE_NONE}
Filter ${STYLE_GRAY}keys:${STYLE_NONE}
    -a${STYLE_GRAY}: include${STYLE_NONE} all ${STYLE_GRAY}hidden files, hidden and build directories${STYLE_NONE}
    -r${STYLE_GRAY}: include${STYLE_NONE} reordered path parts ${STYLE_GRAY}to search, not only in given order${STYLE_NONE}
Open ${STYLE_GRAY}keys:${STYLE_NONE}
    -f${STYLE_GRAY}:${STYLE_NONE} file manager ${STYLE_GRAY}to be used to open${STYLE_NONE} directory containing result ${STYLE_GRAY}file${STYLE_NONE}
Info ${STYLE_GRAY}keys:${STYLE_NONE}
    -i${STYLE_GRAY}:${STYLE_NONE} info  ${STYLE_GRAY}about ${STYLE_NONE}modified times ${STYLE_GRAY}and${STYLE_NONE} sizes ${STYLE_GRAY}for results,${STYLE_NONE} -I${STYLE_GRAY}:${STYLE_NONE} raw paths
    -p${STYLE_GRAY}:${STYLE_NONE} print ${STYLE_GRAY}underlying ${STYLE_NONE}find command
    -h${STYLE_GRAY}:${STYLE_NONE} help  ${STYLE_GRAY}information${STYLE_NONE}"""

fun main(args: Array<String>) {
    if (isHelpMode(args)) {
        println(helpInfo)
    } else {
        doNewSearch(args)
    }
}

private fun isHelpMode(args: Array<String>) =
    args.all { it == "-h" }

private fun executeCommand(
    command: String,
    trim: Boolean = true,
    redirectStderr: Boolean = false,
    detach: Boolean = false,
): String {
    val commandToExecute = if (redirectStderr) "$command 2>&1" else command
    val fp = popen(commandToExecute, "r") ?: error("Failed to run command: $command")

    val stdout = buildString {
        if (!detach) {
            val buffer = ByteArray(4096)
            while (true) {
                val input = fgets(buffer.refTo(0), buffer.size, fp) ?: break
                append(input.toKString())
            }
        }
    }

    val status = pclose(fp)
    if (status != 0) {
        error("Command `$command` failed with status $status${if (redirectStderr) ": $stdout" else ""}")
    }

    return if (trim) stdout.trimEnd() else stdout
}

private fun openResults(
    results: String,
    resultArgs: Array<String>,
    openContainingFolder: Boolean = false
) {
    val lines = results.lines()
    resultArgs.filterNot { it.startsWith("-") }.map { it.toInt() }.forEach { resultNumber ->
        val fullPath = lines.getOrNull(resultNumber - 1)
        if (!fullPath.isNullOrBlank()) {
            val pathToOpen =
                if (openContainingFolder) {
                    fullPath.replaceAfterLast("/", "")
                } else {
                    fullPath
                }
            val openCommand = "xdg-open"
            println("Opening [${STYLE_BOLD}$resultNumber${STYLE_NONE}]: ${STYLE_GRAY}$openCommand${STYLE_NONE} \"$pathToOpen\"")
            executeCommand("$openCommand \"$pathToOpen\"", redirectStderr = true, detach = true)
        } else {
            println("${"Error:".highlighted(STYLE_RED)} No result $resultNumber to open.")
        }
    }
}

private fun String.isSearchFolder() =
    this == "." || this == ".." || startsWith("./") || startsWith("../")
            || startsWith("/") || startsWith("~")

private fun String.addTrailingSlash() =
    if (endsWith("/")) this else "$this/"

private fun String.resolvePrefixFolder(prefix: String, replacement: String) =
    if (startsWith(prefix)) replaceFirst(prefix, replacement) else this

private fun doNewSearch(args: Array<String>) {
    val homeFolder = executeCommand("echo \$HOME")
    val currentFolder = executeCommand("echo \$PWD")
    val parentFolder = executeCommand("echo \$(cd ../ && pwd)")

    val searchFolders = args
        .takeWhile { it != "-" }
        .filter { it.isSearchFolder() }
        .run { takeIf { it.isNotEmpty() } ?: listOf(defaultFolder) }
        .map { it.resolvePrefixFolder("~", homeFolder) }
        .map { it.resolvePrefixFolder("..", parentFolder) }
        .map { it.resolvePrefixFolder(".", currentFolder) }
        .map { it.addTrailingSlash() }

    val pathArgs = args
        .takeWhile { it != "-" }
        .filterNot { it.startsWith("/") }
        .flatMap { arg -> arg.split("[^\\w-_/.*]+".toRegex()) }
        .filter { it.isNotEmpty() }
    val resultNumberArgs = args
        .dropWhile { it != "-" }
        .drop(1) // :
        .flatMap { arg -> arg.split("[^\\w-_]+".toRegex()) }
        .filter { it.all { it.isDigit() } }

    val includedPathParts = pathArgs
        .filterNot { it.startsWith("-") }
        .filterNot { it.isSearchFolder() }
        .map { it.replace("*", "") }
        .filter { it.isNotBlank() }

    val isAnyPathPartsOrder = pathArgs.contains("-r")
    val includedPathQuery =
        if (isAnyPathPartsOrder) {
            includedPathParts.joinToString(
                prefix = "-ipath \"*",
                separator = "*\" -ipath \"*",
                postfix = "*\""
            )
        } else {
            val pathQuery =
                includedPathParts.joinToString(prefix = "*", separator = "*", postfix = "*")
            "-ipath \"$pathQuery\""
        }

    val excludedPathParts = pathArgs.getExcludedParts()

    val isAll = args.contains("-a")
    val defaultExclusion =
        if (isAll) "" else " ! -ipath \"*/.*\" ! -ipath \"*/build/*\""

    val customExclusions =
        excludedPathParts.takeIf { it.isNotEmpty() }?.joinToString(
            prefix = " ! -ipath \"*",
            separator = "*\" ! -ipath \"*",
            postfix = "*\""
        ) ?: ""

    val sortingDirectionParam =
        if (args.any { it in listOf("-N", "-S", "-m") }) "-r " else ""

    val fileMetadataDateFormat = "%TY-%Tm-%Td %TH:%TM:%.2TS "
    val fileDateStringLength = "1970-01-01 00:00:00 ".length

    val fileMetadataSizeFormat = "%12sᴮ "
    val fileSizeStringLength = "111222333444ᴮ ".length

    val fileMetadataStringLength = fileDateStringLength + fileSizeStringLength
    val skipCharactersSortArg =
        when {
            args.any { it in listOf("-n", "-N") } -> {
                "-t ':' -k 3.${fileSizeStringLength + 2} " // file size in column 3, skip seconds
            }

            args.any { it in listOf("-s", "-S") } -> {
                "-n -k 3 " // file size is column 3
            }

            else -> {
                "-k 1 " // modification time is column 1
            }
        }

    val isShowingFileMetadata = args.contains("-i")
    val cutMetadataCommand = if (isShowingFileMetadata) "" else " | cut -c${fileMetadataStringLength + 3}-"

    val searchFoldersJoined = searchFolders.joinToString(separator = " ")
    val terminalCommand =
        "find $searchFoldersJoined" +
                " -type f $includedPathQuery$customExclusions$defaultExclusion" +
                " -printf \"$fileMetadataDateFormat$fileMetadataSizeFormat%p\\n\" 2>/dev/null" +
                " | sort -u $sortingDirectionParam$skipCharactersSortArg$cutMetadataCommand"

    val searchResultPaths = executeCommand(terminalCommand).trimEnd().lines().filter { line ->
        line.contains("/")
    }

    val resultPathsText = StringBuilder("")
    val resultDigitPlaceCount = ("[" + searchResultPaths.size.toString() + "]")
        .withRangeHighlighted(STYLE_BOLD).withRangeHighlighted(STYLE_BLUE).length
    val isRawRaths = args.contains("-I")
    searchResultPaths.mapIndexed { index, resultLine ->
        val filePath = resultLine.substring(resultLine.indexOf('/'))
        val highlightedLine = resultLine.withRangeHighlighted(
            STYLE_GRAY,
            startPosition = 0,
            endPositionExclusive = resultLine.indexOf('/') +
                    filePath.getLongestPrefixOf(searchFolders).length
        ).withWordsHighlighted(
            includedPathParts, STYLE_YELLOW,
            minPosition = resultLine.indexOf('/'),
            areWordsInSequence = !isAnyPathPartsOrder
        )
        val paddedResultNumber =
            "[${(index + 1).toString().withRangeHighlighted(STYLE_BOLD).withRangeHighlighted(STYLE_BLUE)}]"
                .padStart(resultDigitPlaceCount, '·')
        if (isRawRaths) {
            println(filePath)
        } else {
            println("$paddedResultNumber $highlightedLine")
        }
        resultPathsText.appendLine(filePath)
    }
    val isOpeningContainingFolder = args.contains("-f")
    openResults(resultPathsText.toString(), resultNumberArgs.toTypedArray(), isOpeningContainingFolder)
    val isPrintUnderlyingCommand = args.contains("-p")
    if (isPrintUnderlyingCommand) {
        println("${STYLE_GRAY}Used command: $terminalCommand${STYLE_NONE}")
    }
}

private fun String.getLongestPrefixOf(prefixes: List<String>) =
    prefixes.filter { startsWith(it) }.maxByOrNull { it.length } ?: ""

private fun List<String>.getExcludedParts() =
    filter { it.startsWith("-") }
        .filter { it.length > 2 }
        .map { it.removePrefix("-").lowercase() }


private fun String.highlighted(terminalTextColor: String) =
    withWordsHighlighted(listOf(this), terminalTextColor)

private fun String.withRangeHighlighted(
    terminalTextColor: String, endColor: String = STYLE_NONE,
    startPosition: Int = 0, endPositionExclusive: Int = this.length,
): String {
    val highlightedLine = StringBuilder(this)
    highlightedLine.insert(startPosition, terminalTextColor)
    highlightedLine.insert(endPositionExclusive + terminalTextColor.length, endColor)
    return highlightedLine.toString()
}

private fun String.withWordsHighlighted(
    highlightWords: List<String>,
    terminalTextColor: String,
    areWordsInSequence: Boolean = true,
    minPosition: Int = 0,
): String {
    val highlightedLine = StringBuilder(this)
    val highlightedLineLowerCase = StringBuilder(this.lowercase())
    var highlightingPosition = minPosition
    highlightWords.forEach { highlightWord ->
        val minIndexToHighlight = if (areWordsInSequence) highlightingPosition else minPosition
        highlightingPosition = highlightedLineLowerCase.indexOf(highlightWord.lowercase(), minIndexToHighlight)
        if (highlightingPosition >= 0) {
            highlightedLine.insert(highlightingPosition, terminalTextColor)
            highlightedLineLowerCase.insert(highlightingPosition, terminalTextColor)
            highlightingPosition += highlightWord.length + terminalTextColor.length
            highlightedLine.insert(highlightingPosition, STYLE_NONE)
            highlightedLineLowerCase.insert(highlightingPosition, STYLE_NONE)
            highlightingPosition += STYLE_NONE.length
        }
    }
    return highlightedLine.toString()
}
