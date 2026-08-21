package com.example.lab3.config;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * {@code local} 프로필 — Docker 없이 띄우기 위한 대체 구현.
 *
 * <p>기본 구성은 pgvector(벡터) + JDBC(메모리)를 쓰지만, 그러려면 Postgres 가 떠 있어야 한다.
 * 수업이나 데모에서 Docker 를 못 쓰는 상황을 위해 둘 다 인메모리로 바꾼다.
 *
 * <p><b>한계</b> — 재시작하면 인제스트한 문서와 대화 이력이 모두 사라진다.
 * 또 {@code SimpleVectorStore} 는 필터식 삭제를 지원하지 않아 재색인 시
 * 중복 제거가 동작하지 않는다. 그래서 기본값은 어디까지나 pgvector 다.
 */
@Configuration
@Profile("local")
public class LocalStoreConfig {

    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    @Bean
    ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }
}
