package com.intellij.ml.llm.template.mcp

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.ml.llm.template.utils.PsiUtils
import com.intellij.ml.llm.template.utils.EFCandidateFactory
import com.intellij.ml.llm.template.models.GPTExtractFunctionRequestProvider
import com.intellij.ml.llm.template.models.sendChatRequest
import com.intellij.ml.llm.template.prompts.fewShotExtractSuggestion
import com.intellij.ml.llm.template.utils.identifyExtractFunctionSuggestions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.diagnostic.Logger
import io.ktor.utils.io.streams.*
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

@Serializable
data class GetCandidatesArgs(val filePath: String, val line: Int? = null)

class MCPRefactoringServer {
    private val logger = Logger.getInstance(MCPRefactoringServer::class.java)
    private var serverJob: Job? = null

    fun start() {
        logger.info("Starting MCP Refactoring Server...")
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            val server = Server(
                Implementation("em-assist", "0.1.0"),
                ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)))
            )

            val transport = StdioServerTransport(
                System.`in`.asInput(),
                System.out.asSink().buffered()
            )
            runBlocking {
                server.connect(transport)
                val done = Job()
                server.onClose {
                    done.complete()
                }
                done.join()
            }

            server.addTool(
                name = "example-tool",
                description = "An example tool",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        put("input", buildJsonObject { put("type", "string") })
                    }
                )
            ) { request ->
                CallToolResult(content = listOf(TextContent("Hello, world!")))
            }

            embeddedServer(CIO, host = "127.0.0.1", port = 8001) {
                mcp {
                    server
                }
            }.start(wait = true)

            server.addTool(
                name = "list_extract_function_candidates",
                description = "Lists code fragments that can be extracted into a new function in a given file.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("state") {
                            put("type", "string")
                            put("description", "Two-letter US state code (e.g. CA, NY)")
                        }
                    },
                    required = listOf("state")
                )
            ) { args ->
                val result = CompletableDeferred<CallToolResult>()

                ApplicationManager.getApplication().executeOnPooledThread {
                    val project = ProjectManager.getInstance().openProjects.firstOrNull()
                    if (project == null) {
                        result.complete(CallToolResult(content = listOf(TextContent("No open project found")), isError = true))
                        return@executeOnPooledThread
                    }

//                    val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(File(args.filePath))
//                    if (virtualFile == null) {
//                        result.complete(CallToolResult(content = listOf(TextContent("File not found: ${args.filePath}")), isError = true))
//                        return@executeOnPooledThread
//                    }

//                    ApplicationManager.getApplication().runReadAction {
//                        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
//                        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
//
//                        if (psiFile == null || document == null) {
//                            result.complete(CallToolResult(content = listOf(TextContent("Could not parse file")), isError = true))
//                            return@runReadAction
//                        }
//
//                        val offset = if (args.line != null) {
//                            document.getLineStartOffset(args.line - 1)
//                        } else {
//                            0
//                        }
//
//                        val element = psiFile.findElementAt(offset)
//                        val namedElement = if (element != null) {
//                            PsiUtils.getParentFunctionOrNull(element, psiFile.language)
//                        } else {
//                            null
//                        }
//
//                        if (namedElement == null) {
//                            result.complete(CallToolResult(content = listOf(TextContent("No function found at location")), isError = true))
//                            return@runReadAction
//                        }
//
//                        val codeSnippet = namedElement.text
//                        val startLineNumber = document.getLineNumber(namedElement.textRange.startOffset) + 1
//
//                        // This part usually involves calling the LLM, which we'll do outside the read action
//                        CoroutineScope(Dispatchers.IO).launch {
//                            try {
//                                val messageList = fewShotExtractSuggestion(codeSnippet)
//                                val response = sendChatRequest(
//                                    project, messageList, GPTExtractFunctionRequestProvider.chatModel, GPTExtractFunctionRequestProvider
//                                )
//
//                                if (response == null || response.getSuggestions().isEmpty()) {
//                                    result.complete(CallToolResult(content = listOf(TextContent("No suggestions from LLM")), isError = false))
//                                    return@launch
//                                }
//
//                                val llmResponse = response.getSuggestions()[0]
//                                val efSuggestionList = identifyExtractFunctionSuggestions(llmResponse.text)
//
//                                // We need an editor to build candidates, but since we are headless,
//                                // we might need to mock it or refactor EFCandidateFactory to only need document/file.
//                                // For now, we'll return the raw suggestions.
//
//                                val suggestionsText = efSuggestionList.suggestionList.joinToString("\n") {
//                                    "- ${it.functionName} (lines ${it.lineStart}-${it.lineEnd})"
//                                }
//
//                                result.complete(CallToolResult(content = listOf(TextContent("Found candidates:\n$suggestionsText")), isError = false))
//                            } catch (e: Exception) {
//                                result.complete(CallToolResult(content = listOf(TextContent("Error processing suggestions: ${e.message}")), isError = true))
//                            }
//                        }
//                    }
                }

                result.await()
            }

//            try {
//                embeddedServer(CIO, port = 8081) {
//                    install(SSE)
//                    routing {
//                        val transport = SseServerTransport("/mcp", )
//                        val done = Job()
//                        launch {
//                            server.connect(transport)
//                            done.complete()
//                        }
//                        done.join()
//                    }
//                }.start(wait = true)
//            } catch (e: Exception) {
//                logger.error("Failed to start MCP server", e)
//            }
        }
    }


    fun stop() {
        serverJob?.cancel()
    }
}
