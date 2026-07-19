package study.assistant.ai;

import study.assistant.document.Chunk;
import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class AiService {
    private final String apiKey;
    private final HttpClient httpClient;
    private final Gson gson;
    private final Map<String, float[]> embeddingCache = new ConcurrentHashMap<>();

    public AiService() {
        this.apiKey = loadApiKey();
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        this.gson = new Gson();
        
        if (this.apiKey == null || this.apiKey.isBlank()) {
            System.err.println("WARNING: GEMINI_API_KEY is not defined in environment variables or in your ~/.env file. AI-powered features will fail.");
        } else {
            System.out.println("GEMINI_API_KEY loaded successfully.");
        }
    }

    public boolean isApiKeyAvailable() {
        return this.apiKey != null && !this.apiKey.isBlank();
    }

    private String loadApiKey() {
        // 1. Try env variable
        String envKey = System.getenv("GEMINI_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey;
        }

        // 2. Try user home directory .env file (C:\Users\91934\.env)
        String homeDir = System.getProperty("user.home");
        Path homeEnv = Path.of(homeDir, ".env");
        String key = extractKeyFromEnvFile(homeEnv);
        if (key != null) return key;

        // 3. Try current directory .env file
        Path localEnv = Path.of(".env");
        key = extractKeyFromEnvFile(localEnv);
        if (key != null) return key;

        return null;
    }

    private String extractKeyFromEnvFile(Path path) {
        if (Files.exists(path)) {
            try {
                List<String> lines = Files.readAllLines(path);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("GEMINI_API_KEY=")) {
                        return trimmed.substring(trimmed.indexOf('=') + 1).replace("\"", "").replace("'", "").trim();
                    }
                }
            } catch (Exception e) {
                System.err.println("Error reading env file at " + path + ": " + e.getMessage());
            }
        }
        return null;
    }

    public float[] getEmbedding(String text) {
        if (!isApiKeyAvailable()) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured.");
        }

        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent?key=" + apiKey;
            
            Map<String, Object> partsMap = Map.of("text", text);
            Map<String, Object> contentMap = Map.of("parts", List.of(partsMap));
            Map<String, Object> requestMap = Map.of(
                "model", "models/gemini-embedding-001",
                "content", contentMap
            );
            
            String requestBody = gson.toJson(requestMap);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Embedding API error (" + response.statusCode() + "): " + response.body());
            }

            EmbeddingResponse parsed = gson.fromJson(response.body(), EmbeddingResponse.class);
            if (parsed == null || parsed.embedding == null || parsed.embedding.values == null) {
                throw new RuntimeException("Invalid response format from embedding API: " + response.body());
            }

            return parsed.embedding.values;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch embedding: " + e.getMessage(), e);
        }
    }

    public void embedChunks(List<Chunk> chunks) {
        for (Chunk chunk : chunks) {
            if (!embeddingCache.containsKey(chunk.id())) {
                try {
                    float[] embedding = getEmbedding(chunk.content());
                    embeddingCache.put(chunk.id(), embedding);
                    // Add a tiny delay to avoid hitting aggressive API rate limits
                    Thread.sleep(150);
                } catch (Exception e) {
                    System.err.println("Error embedding chunk " + chunk.id() + ": " + e.getMessage());
                }
            }
        }
    }

    public List<EmbeddedChunk> getEmbeddedChunks(List<Chunk> chunks) {
        List<EmbeddedChunk> embedded = new ArrayList<>();
        for (Chunk chunk : chunks) {
            float[] emb = embeddingCache.get(chunk.id());
            if (emb != null) {
                embedded.add(new EmbeddedChunk(chunk, emb));
            }
        }
        return embedded;
    }

    public List<Chunk> findRelevantChunks(String query, List<Chunk> allChunks, int limit) {
        if (allChunks.isEmpty()) {
            return Collections.emptyList();
        }

        // Ensure chunks are embedded
        embedChunks(allChunks);
        List<EmbeddedChunk> embedded = getEmbeddedChunks(allChunks);
        if (embedded.isEmpty()) {
            return Collections.emptyList();
        }

        float[] queryEmbedding = getEmbedding(query);

        // Score similarity
        record ScoredChunk(Chunk chunk, float score) implements Comparable<ScoredChunk> {
            @Override
            public int compareTo(ScoredChunk o) {
                return Float.compare(o.score, this.score); // descending
            }
        }

        List<ScoredChunk> scored = new ArrayList<>();
        for (EmbeddedChunk ec : embedded) {
            float score = cosineSimilarity(queryEmbedding, ec.embedding());
            scored.add(new ScoredChunk(ec.chunk(), score));
        }

        Collections.sort(scored);

        return scored.stream()
                .limit(limit)
                .map(ScoredChunk::chunk)
                .collect(Collectors.toList());
    }

    private float cosineSimilarity(float[] vectorA, float[] vectorB) {
        float dotProduct = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }
        return dotProduct / ((float) Math.sqrt(normA) * (float) Math.sqrt(normB));
    }

    public String generateAnswer(String prompt) {
        if (!isApiKeyAvailable()) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured.");
        }

        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey;

            Map<String, Object> textMap = Map.of("text", prompt);
            Map<String, Object> partsMap = Map.of("parts", List.of(textMap));
            Map<String, Object> contentsMap = Map.of("contents", List.of(partsMap));

            String requestBody = gson.toJson(contentsMap);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Gemini generation API error (" + response.statusCode() + "): " + response.body());
            }

            GenerateResponse parsed = gson.fromJson(response.body(), GenerateResponse.class);
            if (parsed == null || parsed.candidates == null || parsed.candidates.isEmpty() ||
                parsed.candidates.get(0).content == null || parsed.candidates.get(0).content.parts.isEmpty()) {
                throw new RuntimeException("Invalid response format from Gemini generation API: " + response.body());
            }

            return parsed.candidates.get(0).content.parts.get(0).text;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate answer: " + e.getMessage(), e);
        }
    }

    // Helper structures for JSON mapping
    private static class EmbeddingResponse {
        Embedding embedding;
        static class Embedding {
            float[] values;
        }
    }

    private static class GenerateResponse {
        List<Candidate> candidates;
        static class Candidate {
            Content content;
        }
        static class Content {
            List<Part> parts;
        }
        static class Part {
            String text;
        }
    }
}
