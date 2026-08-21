package com.example.lab3.chat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.stereotype.Service;

import com.example.lab3.chat.AnswerDto.Source;
import com.example.lab3.tools.ToolCallTracker;
import com.example.lab3.tools.ToolUsers;

import reactor.core.publisher.Flux;

/**
 * Phase 3 · 5 — 업무 흐름.
 *
 * <p>Advisor 가 근거를 넣어 주지만, <b>출처는 우리가 꺼내 붙여야</b> 한다.
 * 검색된 문서 목록은 응답 컨텍스트({@link RetrievalAugmentationAdvisor#DOCUMENT_CONTEXT})에 담겨 온다.
 */
@Service
public class HelpDeskService {

    private static final Logger log = LoggerFactory.getLogger(HelpDeskService.class);

    private final ChatClient chat;
    private final ToolCallTracker toolCallTracker;

    /** 스트리밍 응답의 출처를 마지막 이벤트로 내보내기 위해 잠시 보관한다. */
    private final Map<String, List<Source>> lastSources = new ConcurrentHashMap<>();

    public HelpDeskService(ChatClient chat, ToolCallTracker toolCallTracker) {
        this.chat = chat;
        this.toolCallTracker = toolCallTracker;
    }

    /**
     * Phase 5 — 대화 ID 규칙을 <b>한 곳에서</b> 만든다.
     *
     * <p>규칙이 흩어지면 사용자끼리 대화가 섞인다. 섞이면 남의 주문 이야기가
     * 다음 사람 답변에 등장한다 — 사고다.
     */
    public static String conversationId(String tenantId, String userId, String sessionId) {
        return "%s:%s:%s".formatted(tenantId, userId, sessionId);
    }

    /**
     * Phase 3 — 동기 질의응답. 답변 + 출처 + 도구 사용 여부를 함께 반환한다.
     */
    public AnswerDto ask(String question, String userId, String sessionId) {
        String conversationId = conversationId("skala", userId, sessionId);
        toolCallTracker.begin(conversationId);

        ChatClientResponse response = chat.prompt()
                .user(question)
                .toolContext(Map.of(
                        ToolUsers.USER_ID, userId,
                        ToolCallTracker.CONVERSATION_ID, conversationId))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .chatClientResponse();

        // 근거 문장은 답변과 대조해야 뽑히므로, 답변을 먼저 꺼낸다
        String text = textOf(response);
        List<Source> sources = sourcesOf(response, text);
        lastSources.put(conversationId, sources);

        // 도구 사용 여부는 응답으로 판정할 수 없다 — 루프가 끝난 마지막 응답에는
        // 도구 호출이 남아 있지 않아 언제나 false 가 나온다. 실행 지점에서 센 값을 쓴다.
        boolean toolUsed = toolCallTracker.count(conversationId) > 0;

        log.info("[ask] conversationId={} 출처={}건 도구사용={}", conversationId, sources.size(), toolUsed);
        return new AnswerDto(text, sources, toolUsed);
    }

    /**
     * Phase 6 — 스트리밍. 첫 글자를 빨리 보여 주기 위한 경로다.
     *
     * <p>스트림에서는 컨텍스트가 조각마다 흐르므로, 출처는 스트림이 끝난 뒤
     * {@link #lastSources(String, String)} 로 꺼내 마지막 이벤트에 실어 보낸다.
     */
    public Flux<String> stream(String question, String userId, String sessionId) {
        String conversationId = conversationId("skala", userId, sessionId);
        lastSources.remove(conversationId);
        toolCallTracker.begin(conversationId);

        return chat.prompt()
                .user(question)
                .toolContext(Map.of(
                        ToolUsers.USER_ID, userId,
                        ToolCallTracker.CONVERSATION_ID, conversationId))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .chatClientResponse()
                .doOnNext(r -> {
                    // 스트리밍 중에는 답변이 아직 완성되지 않아 근거 문장을 뽑을 수 없다.
                    // 청크 원문은 담기므로 화면에서 펼쳐 볼 수는 있고, 강조만 빠진다.
                    List<Source> s = sourcesOf(r, "");
                    if (!s.isEmpty()) {
                        lastSources.put(conversationId, s);
                    }
                })
                .mapNotNull(this::textOf)
                .filter(t -> !t.isEmpty());
    }

    /** 스트리밍이 끝난 뒤 출처를 꺼낸다. */
    public List<Source> lastSources(String userId, String sessionId) {
        return lastSources.getOrDefault(conversationId("skala", userId, sessionId), List.of());
    }

    // ── 응답에서 꺼내기 ────────────────────────────────────────

    /**
     * 응답 컨텍스트의 검색 문서에서 출처를 뽑고, <b>답변과 겹치는 문장</b>을 함께 담는다.
     *
     * <p>파일명만 돌려주면 사용자는 그 문서 어디가 근거인지 알 수 없다.
     * 청크 원문과 근거 문장을 함께 보내야 화면에서 확인시킬 수 있다.
     */
    @SuppressWarnings("unchecked")
    private List<Source> sourcesOf(ChatClientResponse response, String answer) {
        Object raw = response.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return ((List<Document>) list).stream()
                .map(d -> {
                    String text = d.getText() == null ? "" : d.getText();
                    return new Source(
                            String.valueOf(d.getMetadata().getOrDefault("source", "unknown")),
                            String.valueOf(d.getMetadata().getOrDefault("version", "-")),
                            text,
                            EvidenceExtractor.highlight(text, answer));
                })
                .distinct()
                .toList();
    }

    private String textOf(ChatClientResponse response) {
        if (response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return "";
        }
        String text = response.chatResponse().getResult().getOutput().getText();
        return text == null ? "" : text;
    }

}
