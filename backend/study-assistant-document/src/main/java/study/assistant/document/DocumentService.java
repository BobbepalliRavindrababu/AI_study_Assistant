package study.assistant.document;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DocumentService {
    private final Map<String, Document> documents = new ConcurrentHashMap<>();

    public Document ingestDocument(String name, String content) {
        String docId = UUID.randomUUID().toString();
        List<Chunk> chunks = chunkText(docId, name, content);
        Document doc = new Document(docId, name, content, chunks);
        documents.put(docId, doc);
        return doc;
    }

    public Collection<Document> getDocuments() {
        return documents.values();
    }

    public Optional<Document> getDocument(String id) {
        return Optional.ofNullable(documents.get(id));
    }

    public List<Chunk> getAllChunks() {
        List<Chunk> all = new ArrayList<>();
        for (Document doc : documents.values()) {
            all.addAll(doc.chunks());
        }
        return all;
    }

    private List<Chunk> chunkText(String docId, String docName, String text) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        // Overlapping chunker: ~600 chars, ~150 chars overlap
        int chunkSize = 600;
        int overlap = 150;
        
        // Normalize whitespace to prevent weird indexing
        String normalizedText = text.replaceAll("\\s+", " ").trim();
        
        int start = 0;
        int index = 0;
        while (start < normalizedText.length()) {
            int end = Math.min(start + chunkSize, normalizedText.length());
            
            // Try to break at a space boundary to avoid cut words
            if (end < normalizedText.length()) {
                int lastSpace = normalizedText.lastIndexOf(' ', end);
                if (lastSpace > start + chunkSize / 2) {
                    end = lastSpace;
                }
            }
            
            String content = normalizedText.substring(start, end).trim();
            if (!content.isEmpty()) {
                chunks.add(new Chunk(
                    docId + "-chunk-" + index,
                    docId,
                    docName,
                    index,
                    content
                ));
                index++;
            }
            
            start = end - overlap;
            if (start >= normalizedText.length() || end == normalizedText.length()) {
                break;
            }
            if (start < 0) {
                start = 0;
            }
        }
        
        return chunks;
    }
}
