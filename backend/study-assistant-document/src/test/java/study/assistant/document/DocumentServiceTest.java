package study.assistant.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class DocumentServiceTest {
    private DocumentService documentService;

    @BeforeEach
    public void setUp() {
        documentService = new DocumentService();
    }

    @Test
    public void testIngestAndChunking() {
        String docName = "Syllabus.txt";
        String content = "This is a very long text representing a study syllabus. " +
                "It contains information about Java modules, RAG, and Vite. " +
                "We need to ensure that the document service chunks this content correctly. " +
                "The chunk size is configured to be around 600 characters, " +
                "so a small text like this should probably result in a single chunk.";

        Document doc = documentService.ingestDocument(docName, content);

        assertNotNull(doc);
        assertEquals(docName, doc.name());
        assertFalse(doc.id().isEmpty());
        assertFalse(doc.chunks().isEmpty());
        
        // Assert chunk properties
        Chunk firstChunk = doc.chunks().get(0);
        assertEquals(doc.id(), firstChunk.docId());
        assertEquals(docName, firstChunk.docName());
        assertEquals(0, firstChunk.index());
        assertTrue(firstChunk.content().contains("Java modules"));
    }

    @Test
    public void testEmptyContent() {
        Document doc = documentService.ingestDocument("Empty.txt", "");
        assertNotNull(doc);
        assertTrue(doc.chunks().isEmpty());
    }
}
