package com.intellij.ml.llm.template.mcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.diagnostic.Logger

class ServerStartupActivity : StartupActivity.Background {
    private val logger = Logger.getInstance(ServerStartupActivity::class.java)
    private val server = RefactoringServer()

    override fun runActivity(project: Project) {
        // We only want to start the server once, but runActivity might be called for each project.
        // MCPRefactoringServer could handle idempotency internally or we check here.
        logger.info("Triggering MCP Server startup...")
        server.start()
        
        // Ensure the server stops when the IDE/app is closed
        // In a real plugin, we'd register a listener for app closing.
    }
}
