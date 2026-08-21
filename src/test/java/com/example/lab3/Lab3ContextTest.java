package com.example.lab3;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.example.lab3.chat.HelpDeskService;
import com.example.lab3.rag.IngestService;
import com.example.lab3.tools.OrderTools;
import com.example.lab3.tools.TicketTools;

/**
 * 배선 검증 — 컨텍스트가 뜨고 Phase 1~6 의 빈이 모두 올라오는가.
 *
 * <p>{@code local} 프로필이라 Postgres 도 Docker 도 필요 없고,
 * 모델을 호출하지 않으므로 <b>API 키 없이</b> 돈다.
 */
@SpringBootTest
@ActiveProfiles("local")
class Lab3ContextTest {

    @Autowired private ChatClient helpDeskClient;
    @Autowired private VectorStore vectorStore;
    @Autowired private ChatMemory chatMemory;
    @Autowired private IngestService ingestService;
    @Autowired private HelpDeskService helpDeskService;
    @Autowired private OrderTools orderTools;
    @Autowired private TicketTools ticketTools;

    @Test
    @DisplayName("Phase 1~6 의 빈이 모두 올라온다")
    void 컨텍스트가_뜬다() {
        assertThat(helpDeskClient).as("Phase 1 ChatClient").isNotNull();
        assertThat(vectorStore).as("Phase 2 VectorStore").isNotNull();
        assertThat(chatMemory).as("Phase 5 ChatMemory").isNotNull();
        assertThat(ingestService).as("Phase 2 IngestService").isNotNull();
        assertThat(helpDeskService).as("Phase 3 HelpDeskService").isNotNull();
        assertThat(orderTools).as("Phase 4 OrderTools").isNotNull();
        assertThat(ticketTools).as("Phase 4 TicketTools").isNotNull();
    }

    @Test
    @DisplayName("대화 ID 규칙은 테넌트·사용자·세션을 한 곳에서 조합한다")
    void 대화_ID_규칙() {
        assertThat(HelpDeskService.conversationId("skala", "user1", "s1"))
                .isEqualTo("skala:user1:s1");
    }
}
