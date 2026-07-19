package study.assistant.ai;

import study.assistant.document.Chunk;

public record EmbeddedChunk(Chunk chunk, float[] embedding) {}
