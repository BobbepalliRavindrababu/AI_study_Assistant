package study.assistant.document;

import java.util.List;

public record Document(String id, String name, String content, List<Chunk> chunks) {}
