package com.chattriggers.bot

import com.chattriggers.bot.types.*
import com.google.gson.Gson
import com.jessecorbett.diskord.api.rest.client.ChannelClient
import com.jessecorbett.diskord.dsl.*
import com.jessecorbett.diskord.util.words
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.ws
import io.ktor.http.HttpMethod
import io.ktor.http.cio.websocket.readText
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.yield
import me.xdrop.fuzzywuzzy.FuzzySearch
import java.io.File
import java.util.*

@KtorExperimentalAPI
suspend fun main() {
    CTBot.init()
}

@KtorExperimentalAPI
object CTBot {
    const val MESSAGE_COLOR = 0x7b2fb5
    private const val CHANNEL_ID = "366740283943157760"

    private val gson = Gson()
    private val client = HttpClient(CIO) { install(WebSockets) }
    private lateinit var channel: ChannelClient
    private lateinit var searchTerms: List<SearchTerm>

    suspend fun init() {
        println("Generating KDocs...")
        searchTerms = KDocGenerator.getSearchTerms()
        println("KDocs generated")

        println("Initializing MCPService...")
        MCPService.init()
        println("MCPService initialized")

        println("Building bot...")
        buildBot()
        println("Bot built")

        println("Setting up websockets...")
        setupWebsockets()
        println("Websocket setup")
    }

    private suspend fun setupWebsockets() {
        client.ws(
            method = HttpMethod.Get,
            host = "chattriggers.com",
            path = "/api/events"
        ) {
            pingIntervalMillis = 60000

            while (true) {
                val frame = incoming.receive() as io.ktor.http.cio.websocket.Frame.Text
                val text = frame.readText()

                val event: Any = when {
                    text.contains("release_created") -> gson.fromJson(text, CreateReleaseEvent::class.java)
                    text.contains("module_created") -> gson.fromJson(text, CreateModuleEvent::class.java)
                    text.contains("module_deleted") -> gson.fromJson(text, DeleteModuleEvent::class.java)
                    else -> throw IllegalStateException("Unrecognized websocket response")
                }

                when (event) {
                    is CreateModuleEvent -> channel.onCreateModule(event.module)
                    is CreateReleaseEvent -> channel.onCreateRelease(event.module, event.release)
                    is DeleteModuleEvent -> channel.onDeleteModule(event.module)
                }

                yield()
            }
        }
    }

    private suspend fun buildBot() {
        val token = Properties()
            .apply { load(File(".env.properties").reader()) }
            .getProperty("bot_token")

        bot(token) {
            started {
                channel = clientStore.channels[CHANNEL_ID]
            }

            commands(prefix = "!") {
                command("javadocs") {
                    val top = FuzzySearch.extractTop(words[1], searchTerms, { it.name }, 5).map { it.referent }

                    reply("") {
                        title = "Search results for \"${words[1]}\""

                        description = top.joinToString("\n") {
                            "[${it.descriptor}](${it.url})"
                        }
                    }
                }

                command("mcp") {
                    if (words.size < 3) {
                        channel.helpMessage("Too few arguments provided to `!mcp` command")
                        return@command
                    }

                    val type = when (val t = words[1].toLowerCase()) {
                        "field", "method", "class" -> t
                        else -> {
                            channel.helpMessage("Unrecognized type. Valid types are: `method`, `field`, `class`")
                            return@command
                        }
                    }

                    val isObf = words[2].startsWith("func_") || words[2].startsWith("field_")
                    val third = if (words.size > 3) words[3] else null

                    when (type) {
                        "field" -> {
                            val fields = MCPService.fieldsFromName(words[2], isObf)
                            channel.mcpFieldMessage(words[2], isObf, fields, author.username, third)
                        }
                        "method" -> {
                            val methods = MCPService.methodsFromName(words[2], isObf)
                            channel.mcpMethodMessage(words[2], isObf, methods, author.username, third)
                        }
                        "class" -> {
                            val classes = MCPService.classesFromName(words[2])
                            channel.mcpClassMessage(words[2], classes, author.username)
                        }
                    }
                }

                command("help") {
                    channel.helpMessage()
                }
            }
        }
    }
}