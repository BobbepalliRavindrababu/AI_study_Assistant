package study.assistant.api;

import study.assistant.document.Chunk;
import study.assistant.document.Document;
import study.assistant.document.DocumentService;
import study.assistant.ai.AiService;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

public class ApiServer {
    private final int port;
    private final DocumentService documentService;
    private final AiService aiService;
    private final Gson gson;
    private HttpServer server;

    public ApiServer(int port, DocumentService documentService, AiService aiService) {
        this.port = port;
        this.documentService = documentService;
        this.aiService = aiService;
        this.gson = new Gson();
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        
        server.createContext("/api/ingest", new IngestHandler());
        server.createContext("/api/documents", new DocumentsHandler());
        server.createContext("/api/chat", new ChatHandler());
        server.createContext("/api/summarise", new SummariseHandler());
        server.createContext("/api/clarify", new ClarifyHandler());
        server.createContext("/api/status", new StatusHandler());

        // Use a multi-threaded executor to handle multiple client requests concurrently
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();
        System.out.println("HTTP Server started on port " + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            System.out.println("HTTP Server stopped.");
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, Object responseObj) throws IOException {
        byte[] bytes = gson.toJson(responseObj).getBytes(StandardCharsets.UTF_8);
        
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleOptions(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.sendResponseHeaders(204, -1);
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    // Handlers
    private class IngestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }

            try {
                String body = readBody(exchange);
                IngestRequest req = gson.fromJson(body, IngestRequest.class);
                if (req == null || req.name == null || req.content == null || req.content.isBlank()) {
                    sendResponse(exchange, 400, Map.of("error", "Missing name or content"));
                    return;
                }

                Document doc = documentService.ingestDocument(req.name, req.content);
                
                // Pre-embed chunks asynchronously to make chat queries snappy
                if (aiService.isApiKeyAvailable()) {
                    new Thread(() -> aiService.embedChunks(doc.chunks())).start();
                }

                Map<String, Object> resp = Map.of(
                    "id", doc.id(),
                    "name", doc.name(),
                    "chunkCount", doc.chunks().size(),
                    "message", "Document ingested successfully"
                );
                sendResponse(exchange, 200, resp);
            } catch (Exception e) {
                sendResponse(exchange, 500, Map.of("error", e.getMessage()));
            }
        }
    }

    private class DocumentsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }

            try {
                Collection<Document> docs = documentService.getDocuments();
                List<Map<String, Object>> metadataList = new ArrayList<>();
                for (Document doc : docs) {
                    metadataList.add(Map.of(
                        "id", doc.id(),
                        "name", doc.name(),
                        "chunkCount", doc.chunks().size()
                    ));
                }
                sendResponse(exchange, 200, metadataList);
            } catch (Exception e) {
                sendResponse(exchange, 500, Map.of("error", e.getMessage()));
            }
        }
    }

    private class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }

            try {
                String body = readBody(exchange);
                ChatRequest req = gson.fromJson(body, ChatRequest.class);
                if (req == null || req.query == null || req.query.isBlank()) {
                    sendResponse(exchange, 400, Map.of("error", "Missing query"));
                    return;
                }

                List<Chunk> allChunks;
                if (req.docId != null && !req.docId.isBlank()) {
                    Optional<Document> doc = documentService.getDocument(req.docId);
                    if (doc.isEmpty()) {
                        sendResponse(exchange, 404, Map.of("error", "Document not found"));
                        return;
                    }
                    allChunks = doc.get().chunks();
                } else {
                    allChunks = documentService.getAllChunks();
                }

                if (allChunks.isEmpty()) {
                    sendResponse(exchange, 200, Map.of(
                        "answer", "Please upload some study documents first so I can assist you with your concepts!",
                        "chunks", Collections.emptyList()
                    ));
                    return;
                }

                // If API key is not configured, warn and return simple response
                if (!aiService.isApiKeyAvailable()) {
                    sendResponse(exchange, 200, Map.of(
                        "answer", "Hi! The backend server is currently running, but the GEMINI_API_KEY environment variable is missing. Please configure it to enable Retrieval-Augmented Generation.",
                        "chunks", Collections.emptyList()
                    ));
                    return;
                }

                // Perform RAG: find 4 most relevant chunks
                List<Chunk> relevant = aiService.findRelevantChunks(req.query, allChunks, 4);
                
                // Construct prompt
                StringBuilder promptBuilder = new StringBuilder();
                promptBuilder.append("You are an expert AI Study Assistant. Answer the user's question accurately using ONLY the provided document context sections. ");
                promptBuilder.append("Include citations where appropriate based on the document name. If the answer cannot be found in the context, say so politely.\n\n");
                promptBuilder.append("Context sections:\n");
                for (Chunk c : relevant) {
                    promptBuilder.append("- From [Document: ").append(c.docName()).append("] chunk ").append(c.index()).append(":\n");
                    promptBuilder.append("  \"").append(c.content()).append("\"\n\n");
                }
                promptBuilder.append("User Question: ").append(req.query).append("\n\nAnswer:");

                String answer = aiService.generateAnswer(promptBuilder.toString());

                sendResponse(exchange, 200, Map.of(
                    "answer", answer,
                    "chunks", relevant
                ));
            } catch (Exception e) {
                sendResponse(exchange, 500, Map.of("error", e.getMessage()));
            }
        }
    }

    private class SummariseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }

            try {
                String body = readBody(exchange);
                SummariseRequest req = gson.fromJson(body, SummariseRequest.class);
                if (req == null || req.docId == null) {
                    sendResponse(exchange, 400, Map.of("error", "Missing docId"));
                    return;
                }

                Optional<Document> docOpt = documentService.getDocument(req.docId);
                if (docOpt.isEmpty()) {
                    sendResponse(exchange, 404, Map.of("error", "Document not found"));
                    return;
                }

                Document doc = docOpt.get();
                if (doc.content().isBlank()) {
                    sendResponse(exchange, 200, Map.of("summary", "Document is empty."));
                    return;
                }

                if (!aiService.isApiKeyAvailable()) {
                    sendResponse(exchange, 200, Map.of("summary", "[GEMINI_API_KEY missing] Summary cannot be generated. Please configure your API key."));
                    return;
                }

                // Create a brief summary prompt based on the content (limit content length to prevent token overflow)
                String truncatedContent = doc.content().substring(0, Math.min(doc.content().length(), 15000));
                String prompt = "You are an expert tutor. Summarise the following study material named \"" + doc.name() + "\" in a clear, structured way. " +
                        "Break it down into: \n1. High-level Summary\n2. Key Concepts & Definitions\n3. Core Takeaways.\n\n" +
                        "Content:\n" + truncatedContent;

                String summary = aiService.generateAnswer(prompt);
                sendResponse(exchange, 200, Map.of("summary", summary));
            } catch (Exception e) {
                sendResponse(exchange, 500, Map.of("error", e.getMessage()));
            }
        }
    }

    private class ClarifyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }

            try {
                String body = readBody(exchange);
                ClarifyRequest req = gson.fromJson(body, ClarifyRequest.class);
                if (req == null || req.concept == null || req.concept.isBlank()) {
                    sendResponse(exchange, 400, Map.of("error", "Missing concept"));
                    return;
                }

                if (!aiService.isApiKeyAvailable()) {
                    sendResponse(exchange, 200, Map.of("explanation", "[GEMINI_API_KEY missing] Clarification unavailable. Please configure your API key."));
                    return;
                }

                String prompt = "You are an AI Study Assistant clarifying concepts for a student. " +
                        "Explain the concept of \"" + req.concept + "\" in a clear, engaging, and structured format.\n" +
                        "Use the following structure:\n" +
                        "## Concept Clarification: " + req.concept + "\n" +
                        "### 1. Simple Definition\n(A plain-english explanation that is easy to understand)\n\n" +
                        "### 2. Key Elements\n(Bullet points detailing the core components of the concept)\n\n" +
                        "### 3. Real-World Analogy\n(An everyday comparison that makes it click)\n\n" +
                        "### 4. Interactive Concept Map (Mermaid)\n(Provide a clean, valid Mermaid diagram showing the relationship between elements. Syntax: ```mermaid\\n graph TD\\n ... \\n```)\n\n" +
                        (req.context != null ? "Use this extra context to tailor the explanation:\n" + req.context : "");

                String explanation = aiService.generateAnswer(prompt);
                sendResponse(exchange, 200, Map.of("explanation", explanation));
            } catch (Exception e) {
                sendResponse(exchange, 500, Map.of("error", e.getMessage()));
            }
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }

            Map<String, Object> resp = Map.of(
                "status", "healthy",
                "apiKeyConfigured", aiService.isApiKeyAvailable(),
                "documentCount", documentService.getDocuments().size(),
                "totalChunks", documentService.getAllChunks().size()
            );
            sendResponse(exchange, 200, resp);
        }
    }

    // DTOs
    private static class IngestRequest {
        String name;
        String content;
    }

    private static class ChatRequest {
        String query;
        String docId;
    }

    private static class SummariseRequest {
        String docId;
    }

    private static class ClarifyRequest {
        String concept;
        String context;
    }
}
