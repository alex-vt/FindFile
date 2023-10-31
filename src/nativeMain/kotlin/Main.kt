// FindFile, a file search util for command line; see helpInfo below
// SPDX-FileCopyrightText: © 2022-2023 Oleksii Kovtun <alexvt.com>
// SPDX-License-Identifier: MIT
import kotlinx.cinterop.*
import net.thauvin.erik.urlencoder.UrlEncoderUtil
import platform.posix.*

private const val DEFAULT_DIR_ENV_VAR_NAME = "FF_DEFAULT_DIR"

@OptIn(ExperimentalForeignApi::class)
private val defaultFolder =
    getenv(DEFAULT_DIR_ENV_VAR_NAME)?.toKString()?.takeIf { it.isNotBlank() }
        ?: "~"

private const val STYLE_NONE = "\u001B[0m"
private const val STYLE_BOLD = "\u001B[1m"
private const val STYLE_YELLOW = "\u001B[33m"
private const val STYLE_BLUE = "\u001B[94m"
private const val STYLE_GRAY = "\u001B[37m"

private val helpInfo = """FindFile, a file search utility used similarly to online search engines.
    ${STYLE_GRAY}A find command wrapper with added highlights and formatting.${STYLE_NONE}
    Searches files containing given path parts, in $defaultFolder by default.
    Excludes results containing any of path parts with leading dashes.
    Shows results in a numbered list of full paths.
    Can select results by given numbers. ${STYLE_GRAY}Mind that results may change.
    * Asterisks around path parts are optional, are used automatically.
    Directories to search in, excluded parts and result numbers are optional.
    Default directory can be set in FF_DEFAULT_DIR environment variable.
    Use args separately, like -p -i -n (-pin is treated as excluded path part).${STYLE_NONE}
Usage:
    ff <include>
    ff <dir> <dir> <include> <include> -<exclude> -<exclude> -<result_number>
Sort ${STYLE_GRAY}args (one at a time):${STYLE_NONE}
    -n ${STYLE_GRAY}(${STYLE_NONE}-N${STYLE_GRAY}):${STYLE_NONE} sort ${STYLE_GRAY}by${STYLE_NONE}  name           ${STYLE_GRAY}a to Z        (Z to a)${STYLE_NONE}
    -s ${STYLE_GRAY}(${STYLE_NONE}-S${STYLE_GRAY}):${STYLE_NONE} sort ${STYLE_GRAY}by${STYLE_NONE}  size           ${STYLE_GRAY}small to big  (big to small)${STYLE_NONE}
    -m ${STYLE_GRAY}(${STYLE_NONE}-M${STYLE_GRAY}):${STYLE_NONE} sort ${STYLE_GRAY}by${STYLE_NONE}  modified time  ${STYLE_GRAY}new to old    (old to new, default)${STYLE_NONE}
Filter ${STYLE_GRAY}args:${STYLE_NONE}
    -a${STYLE_GRAY}: include${STYLE_NONE} all ${STYLE_GRAY}hidden files, hidden and build directories${STYLE_NONE}
    -r${STYLE_GRAY}: include${STYLE_NONE} reordered path parts ${STYLE_GRAY}to search, not only in given order${STYLE_NONE}
    -d${STYLE_GRAY}: search${STYLE_NONE}  directories ${STYLE_GRAY}instead of files, will not go into the found ones${STYLE_NONE}
Select ${STYLE_GRAY}args (one at a time):${STYLE_NONE}
    -q ${STYLE_GRAY}(${STYLE_NONE}-Q${STYLE_GRAY}): select${STYLE_NONE} quoted ${STYLE_GRAY}unformatted result${STYLE_NONE} file ${STYLE_GRAY}(containing${STYLE_NONE} directory${STYLE_GRAY}) ${STYLE_NONE}path
    -o ${STYLE_GRAY}(${STYLE_NONE}-O${STYLE_GRAY}): select quoted and ${STYLE_NONE}open${STYLE_GRAY} each listed${STYLE_NONE} file ${STYLE_GRAY}(containing${STYLE_NONE} directory${STYLE_GRAY})${STYLE_NONE}
Info ${STYLE_GRAY}args:${STYLE_NONE}
    -i${STYLE_GRAY}:${STYLE_NONE} info  ${STYLE_GRAY}about ${STYLE_NONE}modified times ${STYLE_GRAY}and${STYLE_NONE} sizes ${STYLE_GRAY}for results${STYLE_NONE}
    -f ${STYLE_GRAY}(${STYLE_NONE}-F${STYLE_GRAY}):  show as${STYLE_NONE}  file://${STYLE_GRAY} links  for paths that need escaping  (always)${STYLE_NONE}
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

    @OptIn(ExperimentalForeignApi::class)
    val fp = popen(commandToExecute, "r") ?: error("Failed to run command: $command")

    val stdout = buildString {
        if (!detach) {
            val buffer = ByteArray(4096)
            while (true) {
                @OptIn(ExperimentalForeignApi::class)
                val input = fgets(buffer.refTo(0), buffer.size, fp) ?: break
                @OptIn(ExperimentalForeignApi::class)
                append(input.toKString())
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    val status = pclose(fp)
    if (status != 0) {
        error("Command `$command` failed with status $status${if (redirectStderr) ": $stdout" else ""}")
    }

    return if (trim) stdout.trimEnd() else stdout
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
        .filter { it.isSearchFolder() }
        .run { takeIf { it.isNotEmpty() } ?: listOf(defaultFolder) }
        .map { it.resolvePrefixFolder("~", homeFolder) }
        .map { it.resolvePrefixFolder("..", parentFolder) }
        .map { it.resolvePrefixFolder(".", currentFolder) }
        .map { it.addTrailingSlash() }

    val pathArgs = args
        .filterNot { it.startsWith("/") }
        .flatMap { arg -> arg.split("[^\\w-_/.*]+".toRegex()) }
        .filter { it.isNotEmpty() }
    val selectedResultIndices = args
        .filter { it.length > 1 && it.first() == '-' && it.drop(1).all { it.isDigit() } }
        .map { it.removePrefix("-").toInt() - 1 } // natural count 1+ to index 0+

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

    val skipCharactersSortArg =
        when {
            args.any { it in listOf("-n", "-N") } -> {
                "-k 4,${Int.MAX_VALUE}" // file name is columns 4 and on
            }

            args.any { it in listOf("-s", "-S") } -> {
                "-k 1 -n" // file size is column 1, sort as numbers
            }

            else -> {
                "-k 2,3 " // modification time is columns 2, 3
            }
        }

    val isShowingFileMetadata = args.contains("-i")
    val cutMetadataCommand = if (isShowingFileMetadata) "" else " | cut -d':' -f2- | cut -c7-"

    val isSearchingFolders = args.contains("-d")
    val searchType = if (isSearchingFolders) "d" else "f"
    val pruning = if (isSearchingFolders) " -prune" else ""

    val searchFoldersJoined = searchFolders.joinToString(separator = " ")
    val terminalCommand =
        "find $searchFoldersJoined" +
                " -type $searchType $includedPathQuery$customExclusions$defaultExclusion" +
                "$pruning 2>/dev/null" +
                " -exec du -b -s --time --time-style=\"+%Y-%m-%d %H:%M:%S\" {} +" +
                " | sort $sortingDirectionParam$skipCharactersSortArg$cutMetadataCommand"

    val searchResultPaths = executeCommand(terminalCommand).trimEnd().lines().filter { line ->
        line.contains("/")
    }

    val resultDigitPlaceCount = ("[" + searchResultPaths.size.toString() + "]")
        .withRangeHighlighted(STYLE_BOLD).withRangeHighlighted(STYLE_BLUE).length
    val isFilePaths = args.contains("-q") || args.contains("-o")
    val isContainingFolderPaths = args.contains("-Q") || args.contains("-O")
    val isOpenCommand = args.contains("-o") || args.contains("-O")
    val isQuotedPath = isFilePaths || isContainingFolderPaths
    val isFileLinksOnDemand = args.contains("-f")
    val isFileLinksAlways = args.contains("-F")
    searchResultPaths.map {
        it.withSizeAndTimeReformatted()
    }.mapIndexed { index, resultLine ->
        val filePath = resultLine.substring(resultLine.indexOf('/')).run {
            if (isContainingFolderPaths) {
                replaceAfterLast("/", "")
            } else {
                this
            }
        }
        val fileLinkLine = resultLine.asFileLink(
            isOnDemand = isFileLinksOnDemand,
            isAlways = isFileLinksAlways,
        )
        val highlightedLine = fileLinkLine.withRangeHighlighted(
            STYLE_GRAY,
            startPosition = 0,
            endPositionExclusive = fileLinkLine.indexAfterFirstOf("file:///", "/") - 1
                    + filePath.getLongestPrefixOf(searchFolders).length
        ).withWordsHighlighted(
            includedPathParts, STYLE_YELLOW,
            minPosition = fileLinkLine.indexAfterFirstOf("file:///", "/") - 1,
            areWordsInSequence = !isAnyPathPartsOrder
        )
        val paddedResultNumber =
            "[${(index + 1).toString().withRangeHighlighted(STYLE_BOLD).withRangeHighlighted(STYLE_BLUE)}]"
                .padStart(resultDigitPlaceCount, '·')
        val isLineShown =
            index in selectedResultIndices || selectedResultIndices.isEmpty()
        val quotedPath = "\"$filePath\""
        if (isLineShown) {
            if (isQuotedPath) {
                println(quotedPath)
            } else {
                println("$paddedResultNumber $highlightedLine")
            }
            if (isOpenCommand) {
                val openCommand = "open $quotedPath &"
                executeCommand(openCommand, redirectStderr = true, detach = true)
            }
        }
    }
    val isPrintUnderlyingCommand = args.contains("-p")
    if (isPrintUnderlyingCommand) {
        println("${STYLE_GRAY}Used command: $terminalCommand${STYLE_NONE}")
    }
}

private fun String.withSizeAndTimeReformatted(): String {
    if (startsWith("/")) return this // no attributes
    val attributesString = substringBefore('/')
    val path = removePrefix(attributesString)
    val attributes = attributesString.split('\t')
    val size = attributes[0].trim()
    val date = attributes[1].trim()
    val sizePadding = 15
    return "$date ${size.groupedThousands().padStart(sizePadding, ' ')}ᴮ $path"
}

private fun String.groupedThousands(separator: Char = '\''): String =
    reversed()
        .chunked(3).joinToString(separator = separator.toString())
        .reversed()

private fun String.getLongestPrefixOf(prefixes: List<String>) =
    prefixes.filter { startsWith(it) }.maxByOrNull { it.length } ?: ""

private fun List<String>.getExcludedParts() =
    filter { it.startsWith("-") }
        .filter { it.length > 2 }
        .map { it.removePrefix("-").lowercase() }
        .filterNot { it.all { it.isDigit() } } // those are selected result numbers

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

private fun String.asFileLink(isOnDemand: Boolean, isAlways: Boolean): String {
    val escaped = take(indexOf('/')) + UrlEncoderUtil.encode(source = drop(indexOf('/')), allow = "/")
    return if (isAlways || isOnDemand && escaped != this) {
        "${take(indexOf('/'))}file://${escaped.drop(indexOf('/'))}"
    } else {
        this
    }
}

private fun String.indexAfterFirstOf(vararg part: String): Int =
    part.map {
        indexOf(it).run {
            if (this < 0) this else this + it.length
        }
    }.dropWhile { it < 0 }.firstOrNull() ?: -1