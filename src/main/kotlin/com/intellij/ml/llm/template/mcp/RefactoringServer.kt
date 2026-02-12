package com.intellij.ml.llm.template.mcp

import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.ml.llm.template.utils.PsiUtils
import com.intellij.ml.llm.template.models.GPTExtractFunctionRequestProvider
import com.intellij.ml.llm.template.models.sendChatRequest
import com.intellij.ml.llm.template.prompts.fewShotExtractSuggestion
import com.intellij.ml.llm.template.utils.identifyExtractFunctionSuggestions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import com.intellij.ml.llm.template.extractfunction.EFCandidate
import com.intellij.ml.llm.template.intentions.HeadlessExtractFunction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import org.jetbrains.kotlin.idea.base.psi.getLineStartOffset
import java.io.File
import javax.swing.SwingUtilities

@Serializable
data class ExtractFunctionRequest(
    val filePath: String,
    val line: Int? = 1
)

@Serializable
data class ExtractFunctionResponse(
    val candidates: List<EFCandidate>,
    val error: String? = null
)

class RefactoringServer {
    private val logger = Logger.getInstance(RefactoringServer::class.java)
    private var serverJob: Job? = null
    private var isRunning = false

    @Synchronized
    fun start() {
        if (isRunning) {
            logger.info("Refactoring Server is already running.")
            return
        }
        isRunning = true
        logger.info("Starting Refactoring Server...")
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                embeddedServer(CIO, host = "0.0.0.0", port = 8001) {
                    install(ContentNegotiation) {
                        json()
                    }
                    routing {
                        post("/list_extract_function_candidates") {
                            val request = call.receive<ExtractFunctionRequest>()
                            val filePath = request.filePath
                            val line = request.line ?: 1
                            
                            val deferred = CompletableDeferred<ExtractFunctionResponse>()
                            callEmAssist(deferred, filePath, line)
                            
                            val result = deferred.await()
                            call.respond(result)
                        }
                    }
                }.start(wait = true)
            } catch (e: Exception) {
                logger.error("Failed to start Refactoring server", e)
                isRunning = false
            }
        }
    }

    private fun callEmAssist(
        result: CompletableDeferred<ExtractFunctionResponse>,
        filePath: String,
        line: Int
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val project = ProjectManager.getInstance().openProjects.firstOrNull()
            if (project == null) {
                result.complete(ExtractFunctionResponse(emptyList(), "No open project found"))
                return@executeOnPooledThread
            }

            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(File(filePath))
            if (virtualFile == null) {
                result.complete(ExtractFunctionResponse(emptyList(), "File not found: $filePath"))
                return@executeOnPooledThread
            }

            ApplicationManager.getApplication().runReadAction {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                val document = FileDocumentManager.getInstance().getDocument(virtualFile)

                if (psiFile == null || document == null) {
                    result.complete(ExtractFunctionResponse(emptyList(), "Could not parse file"))
                    return@runReadAction
                }
                var editor: Editor? = null
                SwingUtilities.invokeAndWait {

                        editor = FileEditorManager.getInstance(project).openTextEditor(
                            OpenFileDescriptor(
                                project,
                                virtualFile,
                            ),
                            true, // request focus to editor
                        )!!
                }

                val lineStartOffset = document.getLineStartOffset(line - 1)
                SwingUtilities.invokeAndWait{
                    editor?.selectionModel?.setSelection(lineStartOffset, lineStartOffset + 1)
                }

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val headlessEmAssist = HeadlessExtractFunction()
                        SwingUtilities.invokeAndWait{ headlessEmAssist.invoke(project, editor = editor, file = psiFile) }

                        // Wait for the LLM process to complete and set 'completed' to true
                        val timeoutLimit = 120000L // 60 seconds timeout
                        val startTime = System.currentTimeMillis()
                        while (!headlessEmAssist.completed && (System.currentTimeMillis() - startTime) < timeoutLimit) {
                            delay(500)
                        }

                        if (headlessEmAssist.completed) {
                            result.complete(
                                ExtractFunctionResponse(headlessEmAssist.candidates)
                            )
                        } else {
                            result.complete(
                                ExtractFunctionResponse(emptyList(), "Timeout: LLM suggestions took too long or failed.")
                            )
                        }
                    } catch (e: Exception) {
                        result.complete(ExtractFunctionResponse(emptyList(), "Error processing suggestions: ${e.message}"))
                    }
                }
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        isRunning = false
    }
}
