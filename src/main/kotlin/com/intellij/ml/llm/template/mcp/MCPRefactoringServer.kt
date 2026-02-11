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
import io.ktor.server.routing.routing
import com.intellij.ml.llm.template.extractfunction.EFCandidate
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.Editor
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
data class GetCandidatesArgs(val filePath: String, val line: Int? = null)

class MCPRefactoringServer {
    private val logger = Logger.getInstance(MCPRefactoringServer::class.java)
    private var serverJob: Job? = null
    private var isRunning = false
    private val candidatesCache = mutableMapOf<String, List<EFCandidate>>()

    @Synchronized
    fun start() {
        if (isRunning) {
            logger.info("MCP Refactoring Server is already running.")
            return
        }
        isRunning = true
        logger.info("Starting MCP Refactoring Server...")
        serverJob = CoroutineScope(Dispatchers.IO).launch {
        val server = Server(
            Implementation("em-assist", "0.1.0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)))
        )
            // Register tools first
            server.addTool(
                name = "list_extract_function_candidates",
                description = "Lists code fragments that can be extracted into a new function in a given file.",
                inputSchema = GetCandidatesArgs.serializer()
            ) { args ->
                val result = CompletableDeferred<CallToolResult>()

                ApplicationManager.getApplication().executeOnPooledThread {
                    val project = ProjectManager.getInstance().openProjects.firstOrNull()
                    if (project == null) {
                        result.complete(CallToolResult(content = listOf(TextContent("No open project found")), isError = true))
                        return@executeOnPooledThread
                    }

                    val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(File(args.filePath))
                    if (virtualFile == null) {
                        result.complete(CallToolResult(content = listOf(TextContent("File not found: ${args.filePath}")), isError = true))
                        return@executeOnPooledThread
                    }

                    ApplicationManager.getApplication().runReadAction {
                        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                        val document = FileDocumentManager.getInstance().getDocument(virtualFile)

                        if (psiFile == null || document == null) {
                            result.complete(CallToolResult(content = listOf(TextContent("Could not parse file")), isError = true))
                            return@runReadAction
                        }

                        val offset = if (args.line != null) {
                            document.getLineStartOffset(args.line - 1)
                        } else {
                            0
                        }

                        val element = psiFile.findElementAt(offset)
                        val namedElement = if (element != null) {
                            PsiUtils.getParentFunctionOrNull(element, psiFile.language)
                        } else {
                            null
                        }

                        if (namedElement == null) {
                            result.complete(CallToolResult(content = listOf(TextContent("No function found at location")), isError = true))
                            return@runReadAction
                        }

                        val codeSnippet = namedElement.text
                        
                        // Use a background scope for the LLM request
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val messageList = fewShotExtractSuggestion(codeSnippet)
                                val response = sendChatRequest(
                                    project, messageList, GPTExtractFunctionRequestProvider.chatModel, GPTExtractFunctionRequestProvider
                                )

                                if (response == null || response.getSuggestions().isEmpty()) {
                                    result.complete(CallToolResult(content = listOf(TextContent("No suggestions from LLM")), isError = false))
                                    return@launch
                                }

                                val llmResponse = response.getSuggestions()[0]
                                val efSuggestionList = identifyExtractFunctionSuggestions(llmResponse.text)
                                
                                // Build actual candidates to get offsets
                                val candidates = EFCandidateFactory().buildCandidates(efSuggestionList.suggestionList, null, psiFile).toList()
                                candidatesCache[args.filePath] = candidates

                                val suggestionsText = candidates.withIndex().joinToString("\n") { (index, it) ->
                                    "[$index] ${it.functionName} (lines ${it.lineStart}-${it.lineEnd})"
                                }

                                result.complete(CallToolResult(content = listOf(TextContent("Found candidates:\n$suggestionsText\n\nUse apply_extract_function with filePath and index to apply.")), isError = false))
                            } catch (e: Exception) {
                                result.complete(CallToolResult(content = listOf(TextContent("Error processing suggestions: ${e.message}")), isError = true))
                            }
                        }
                    }
                }

                result.await()
            }

            // Start Stdio transport in a separate job so it doesn't block the coroutine
            launch {
                val transport = StdioServerTransport(
                    System.`in`.asInput(),
                    System.out.asSink().buffered()
                )
                server.connect(transport)
                val done = Job()
                server.onClose { done.complete() }
                done.join()
            }

            // Start SSE server if dependencies allow (port 8081 as per plan)
            try {
                embeddedServer(CIO, host = "0.0.0.0", port = 8001) {
                    mcp {
                        server
                    }
                }.start(wait = true)
            } catch (e: NoClassDefFoundError) {
                logger.warn("SSE transport skipped: Ktor server dependencies missing.")
            } catch (e: Exception) {
                logger.error("Failed to start SSE MCP server", e)
            }
        }
    }


    fun stop() {
        serverJob?.cancel()
    }
}
