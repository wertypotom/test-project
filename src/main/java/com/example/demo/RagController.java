package com.example.demo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
@CrossOrigin
public class RagController {

    private final VectorStore store;

    private final ChatClient ragChat;

    public RagController(VectorStore store, @Qualifier("ragChatClient") ChatClient ragChatClient) {
        this.store = store;
        this.ragChat = ragChatClient;
    }

    static List<Document> splitIntoDocs(String text, int maxLen) {
        List<Document> docs = new ArrayList<>();
        if (text == null) return docs;
        String[] parts = text.split("(?<=\\G.{1," + maxLen + "})", -1);
        int i = 0;
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("chunk", i++);
                docs.add(new Document(trimmed, meta));
            }
        }
        return docs;
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestBody Map<String, Object> body) {
        String text = String.valueOf(body.getOrDefault("text", ""));
        int chunk = Math.max(400, Math.min(1600,
                ((Number) body.getOrDefault("chunkSize", 800)).intValue()));
        List<Document> docs = splitIntoDocs(text, chunk);
        store.add(docs);
        return Map.of("stored", docs.size());
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, Object> body) {
        String q = String.valueOf(body.getOrDefault("q", ""));
        String answer = ragChat
                .prompt()
                .system("Answer ONLY using retrieved context. If missing, say you don't know.")
                .user(q)
                .call()
                .content();
        return Map.of("answer", answer);
    }
}
