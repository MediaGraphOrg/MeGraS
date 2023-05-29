package org.megras.api.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.output.HelpFormatter
import org.jline.builtins.Completers
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.completer.AggregateCompleter
import org.jline.reader.impl.completer.StringsCompleter
import org.jline.terminal.TerminalBuilder
import org.megras.data.fs.FileSystemObjectStore
import org.megras.graphstore.MutableQuadSet
import java.io.IOException
import java.util.ArrayList
import java.util.regex.Pattern
import kotlin.system.exitProcess

object Cli {

    private const val PROMPT = "MeGraS> "
    private const val LOGO =  """
  __  __      ___          ___ 
 |  \/  |___ / __|_ _ __ _/ __|
 | |\/| / -_) (_ | '_/ _` \__ \
 |_|  |_\___|\___|_| \__,_|___/
 """

    private lateinit var clikt: CliktCommand

    fun init(quads: MutableQuadSet, objectStore: FileSystemObjectStore) {
        clikt = BaseCommand().subcommands(
            AddFileCommand(quads, objectStore),
            ImportCommand(quads)
        )
    }

    fun loop() {

        val terminal = try {
            TerminalBuilder.builder().build()
        } catch (e: IOException) {
            System.err.println("Could not initialize terminal: ${e.message}")
            exitProcess(-1)
        }

        val completer = AggregateCompleter(
            StringsCompleter("quit", "exit", "help"),
            Completers.TreeCompleter(
                clikt.registeredSubcommands().map {
                    if(it.registeredSubcommands().isNotEmpty()){
                        Completers.TreeCompleter.node(it.commandName, Completers.TreeCompleter.node(*it.registeredSubcommandNames().toTypedArray()))
                    }else{
                        Completers.TreeCompleter.node(it.commandName)
                    }
                }
            ),
            Completers.FileNameCompleter()
        )

        val lineReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(completer)
            .build()

        terminal.writer().println(LOGO)

        while (true) {
            try {
                val line = lineReader.readLine(PROMPT).trim()
                val lower = line.lowercase()
                if (lower == "exit" || lower == "quit") {
                    break
                }
                if (lower == "help") {
                    println(clikt.getFormattedHelp())
                    continue
                }
                if (line.isBlank()) {
                    continue
                }

                try {
                    execute(line)
                } catch (e: Exception) {
                    when (e) {
                        is com.github.ajalt.clikt.core.NoSuchSubcommand -> println("command not found")
                        is com.github.ajalt.clikt.core.PrintHelpMessage -> println(e.command.getFormattedHelp())
                        is com.github.ajalt.clikt.core.NoSuchOption -> println(e.localizedMessage)
                        is com.github.ajalt.clikt.core.UsageError -> println("invalid command")
                        else -> e.printStackTrace()
                    }
                }
            } catch (e: EndOfFileException) {
                System.err.println("Could not read from terminal due to EOF.")
                break
            }  catch (e: UserInterruptException) {
                break
            }
        }
    }

    private fun execute(line: String){
        if(!::clikt.isInitialized){
            error("CLI not initialised. Aborting...") // Technically, this should never ever happen
        }
        clikt.parse(splitLine(line))
    }

    private val lineSplitRegex: Pattern = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'")

    @JvmStatic
    private fun splitLine(line: String): List<String> {
        if (line.isBlank()) {
            return emptyList()
        }
        val matchList: MutableList<String> = ArrayList()
        val regexMatcher = lineSplitRegex.matcher(line)
        while (regexMatcher.find()) {
            if (regexMatcher.group(1) != null) {
                matchList.add(regexMatcher.group(1))
            } else if (regexMatcher.group(2) != null) {
                matchList.add(regexMatcher.group(2))
            } else {
                matchList.add(regexMatcher.group())
            }
        }
        return matchList
    }

    private class BaseCommand : NoOpCliktCommand(name = "megras") {
        init {
            context { helpFormatter = CliHelpFormatter() }
        }
    }

    private class CliHelpFormatter : CliktHelpFormatter() {
        override fun formatHelp(
            prolog: String,
            epilog: String,
            parameters: List<HelpFormatter.ParameterHelp>,
            programName: String
        ) = buildString {
            addOptions(parameters)
            addArguments(parameters)
            addCommands(parameters)
        }
    }

}