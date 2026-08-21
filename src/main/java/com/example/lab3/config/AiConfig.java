package com.example.lab3.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.example.lab3.advisor.AuditAdvisor;
import com.example.lab3.search.HybridDocumentRetriever;
import com.example.lab3.search.KeywordIndex;
import com.example.lab3.tools.OrderTools;
import com.example.lab3.tools.TicketTools;

/**
 * Phase 1 — ChatClient 와 Advisor 체인 조립.
 *
 * <p>ChatClient 하나에 Advisor 체인 전체를 기본값으로 걸어 둔다.
 * 호출부는 {@code chat.prompt().user(...)} 만 하면 되고,
 * RAG·메모리·안전·감사는 자동으로 붙는다.
 *
 * <h2>체인 순서가 곧 정책이다</h2>
 * <pre>
 *   0   SafeGuard   민감어 차단
 *   100 Audit       감사 로깅
 *   200 Memory      대화 이력 주입·저장
 *   300 RAG         문서 근거 주입
 * </pre>
 *
 * <p><b>차단은 저장보다 앞</b>이어야 한다. SafeGuard 가 메모리보다 뒤에 있으면
 * 차단된 요청이 이미 대화 이력에 저장된 뒤라, 다음 턴에 그 내용이 다시 모델로 들어간다.
 */
@Configuration
@EnableConfigurationProperties(HelpDeskProperties.class)
public class AiConfig {

    private static final int ORDER_SAFEGUARD = 0;
    private static final int ORDER_MEMORY = 200;
    private static final int ORDER_RAG = 300;

    /**
     * 대화 메모리 — 최근 N개 메시지만 창(window)으로 유지한다.
     *
     * <p>{@code ChatMemoryRepository} 는 자동 구성이 제공한다.
     * 기본 프로필에서는 JDBC(재시작에도 유지), {@code local} 프로필에서는 인메모리다.
     */
    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository, HelpDeskProperties props) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(props.memory().maxMessages())
                .build();
    }

    /**
     * 헬프데스크 전용 ChatClient.
     *
     * @param skalaHelpdesk {@code prompts/skala-helpdesk.md} — SKALA 공식 시스템 프롬프트
     * @param systemPrompt  {@code prompts/system.st} — 위 문서를 {@code skala_prompt} 로 끼우고
     *                      이 배포의 주문·티켓 도구 규칙을 덧붙인다
     */
    @Bean
    ChatClient helpDeskClient(ChatClient.Builder builder,
                              VectorStore vectorStore,
                              KeywordIndex keywordIndex,
                              ChatMemory chatMemory,
                              HelpDeskProperties props,
                              AuditAdvisor audit,
                              OrderTools orderTools,
                              TicketTools ticketTools,
                              @Value("classpath:prompts/skala-helpdesk.md") Resource skalaHelpdesk,
                              @Value("classpath:prompts/system.st") Resource systemPrompt) {

        String skalaPrompt;
        try {
            skalaPrompt = skalaHelpdesk.getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("prompts/skala-helpdesk.md 를 읽지 못했습니다", e);
        }
        String system = new PromptTemplate(systemPrompt).render(Map.of("skala_prompt", skalaPrompt));

        return builder
                .defaultSystem(system)
                .defaultTools(orderTools, ticketTools)
                .defaultAdvisors(
                        audit,
                        SafeGuardAdvisor.builder()
                                .sensitiveWords(props.safety().sensitiveWords())
                                .failureResponse(props.safety().failureResponse())
                                .order(ORDER_SAFEGUARD)
                                .build(),
                        MessageChatMemoryAdvisor.builder(chatMemory)
                                .order(ORDER_MEMORY)
                                .build(),
                        ragAdvisor(vectorStore, keywordIndex, props))
                .build();
    }

    /**
     * 검색기 선택 — 하이브리드(기본) 또는 벡터 단독.
     *
     * <p>{@code helpdesk.rag.hybrid: false} 로 두면 벡터 단독으로 돌아간다.
     * 설정 하나로 갈아 끼울 수 있어야 <b>같은 골든셋으로 두 방식을 비교</b>할 수 있다 —
     * 코드를 고쳐야 비교되는 구조면 실험이 번거로워 결국 안 하게 된다.
     */
    private DocumentRetriever documentRetriever(VectorStore vectorStore,
                                                KeywordIndex keywordIndex,
                                                HelpDeskProperties props) {
        if (props.rag().hybrid()) {
            return new HybridDocumentRetriever(vectorStore, keywordIndex,
                    props.rag().topK(), props.rag().threshold());
        }
        return VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(props.rag().topK())
                .similarityThreshold(props.rag().threshold())
                .build();
    }

    /**
     * 증강 프롬프트 — <b>기본 템플릿을 그대로 쓰면 멀티턴이 깨진다</b>.
     *
     * <p>Spring AI 기본값은 {@code Given the context information and no prior knowledge,
     * answer the query.} 라고 지시한다. "no prior knowledge" 가 <b>대화 이력까지 쓰지 말라</b>는
     * 뜻으로 작동해서, 앞 턴에서 주문 12345 를 답해 놓고도 "그거 반품 돼요?" 를 연결하지 못한다.
     *
     * <p>사실의 출처는 문맥으로 제한하되, 지시 대상 해석에는 대화 이력을 허용한다 —
     * 사실은 문서에서, 대명사는 대화에서 온다.
     */
    private static final PromptTemplate AUGMENT_TEMPLATE = new PromptTemplate("""
            아래는 사내 문서에서 찾은 참고 자료다.
            ---------------------
            {context}
            ---------------------

            답변 규칙
            1. 사실(금액·기간·비율·조건)은 위 참고 자료나 도구 결과에서만 가져온다.
            2. 대명사나 생략된 대상("그거", "그 주문")은 앞선 대화 내용을 참고해 특정한다.
               앞 대화에 주문번호가 나왔다면 그 주문을 가리키는 것으로 본다.
            3. 참고 자료에도 대화에도 근거가 없으면 모른다고 답한다. 지어내지 않는다.
            

            질문: {query}
            """);

    /**
     * RAG Advisor — 검색된 문서를 질의에 붙여 준다.
     *
     * <p>Spring AI 2.0 에서 {@code QuestionAnswerAdvisor} 는 사라지고
     * {@code RetrievalAugmentationAdvisor} + {@code VectorStoreDocumentRetriever} 로 대체됐다.
     *
     * <p>{@code allowEmptyContext(true)} 로 두는 이유: 근거가 없다고 즉시 중단하면
     * <b>주문 조회 같은 도구 전용 질문까지 막힌다</b>. 규정 질문에서 지어내는 것은
     * 시스템 프롬프트가 막는다.
     */
    private RetrievalAugmentationAdvisor ragAdvisor(VectorStore vectorStore,
                                                    KeywordIndex keywordIndex,
                                                    HelpDeskProperties props) {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever(vectorStore, keywordIndex, props))
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(true)
                        .promptTemplate(AUGMENT_TEMPLATE)
                        .build())
                .order(ORDER_RAG)
                .build();
    }
}
