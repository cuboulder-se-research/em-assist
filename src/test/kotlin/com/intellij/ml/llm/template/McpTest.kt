package com.intellij.ml.llm.template

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.client.Client
import kotlinx.coroutines.runBlocking
import org.junit.Test

class McpTest {

    @Test
    fun `test hello world`(){

        runBlocking {
            val url = "http://localhost:8001/mcp"

            val httpClient = HttpClient { install(SSE) }

            val client = Client(
                clientInfo = Implementation(
                    name = "example-client",
                    version = "1.0.0"
                )
            )

//            val transport = Client(
//                client = httpClient,
//                urlString = url
//            )

            // Connect to server
            client.connect(transport)

            // List available tools
            val tools = client.listTools()

            println(tools)
        }
    }
}