package study.assistant.ai;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

public class AiServiceTest {

    @Test
    public void testCosineSimilarity() throws Exception {
        AiService aiService = new AiService();

        // Cosine similarity is a private method in AiService. 
        // We'll use reflection to test it directly.
        Method method = AiService.class.getDeclaredMethod("cosineSimilarity", float[].class, float[].class);
        method.setAccessible(true);

        // Vector pointing in the same direction (similarity should be 1.0)
        float[] v1 = {1.0f, 2.0f, 3.0f};
        float[] v2 = {2.0f, 4.0f, 6.0f};
        float similarityIdentical = (float) method.invoke(aiService, v1, v2);
        assertEquals(1.0f, similarityIdentical, 0.0001f);

        // Orthogonal vectors (similarity should be 0.0)
        float[] v3 = {1.0f, 0.0f, 0.0f};
        float[] v4 = {0.0f, 1.0f, 0.0f};
        float similarityOrthogonal = (float) method.invoke(aiService, v3, v4);
        assertEquals(0.0f, similarityOrthogonal, 0.0001f);

        // Opposite vectors (similarity should be -1.0)
        float[] v5 = {1.0f, -1.0f};
        float[] v6 = {-1.0f, 1.0f};
        float similarityOpposite = (float) method.invoke(aiService, v5, v6);
        assertEquals(-1.0f, similarityOpposite, 0.0001f);
    }
}
