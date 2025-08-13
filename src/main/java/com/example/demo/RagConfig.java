package com.example.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RagConfig {

    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    // Plain chat (no RAG)
    /*
    * Added some comments to describe functions */
    @Primary
    @Bean(name = "plainChatClient")
    ChatClient plainChatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    // RAG chat (with advisor)
    @Bean(name = "ragChatClient")
    ChatClient ragChatClient(ChatClient.Builder builder, VectorStore vectorStore) {
        var retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore).similarityThreshold(0.00).topK(5).build();
        var ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever).build();
        return builder.defaultAdvisors(ragAdvisor).build();
    }
}
