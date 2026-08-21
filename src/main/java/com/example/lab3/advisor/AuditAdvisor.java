package com.example.lab3.advisor;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;

/**
 * 감사 로깅 — 모든 요청과 도구 호출이 기록에 남는다.
 *
 * <p>AI 서비스에서 "무엇을 근거로 그렇게 답했나"를 사후에 따질 수 없으면
 * 장애도 민원도 규명할 수 없다. 그래서 질의·응답·도구 호출을 남긴다.
 *
 * <p><b>질문 원문은 남기지 않는다</b> — 개인정보가 그대로 쌓이기 때문이다.
 * 길이와 대화 ID만 남겨도 추적에는 충분하다.
 */
@Component
public class AuditAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AuditAdvisor.class);

    /** 체인에서의 위치 — 안전 차단 다음, 메모리 저장 앞 */
    public static final int ORDER = 100;

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Object conversationId = request.context().get(ChatMemory.CONVERSATION_ID);
        int length = request.prompt().getUserMessage() == null
                ? 0 : request.prompt().getUserMessage().getText().length();

        log.info("[audit] 요청 conversationId={} 질의길이={}자", conversationId, length);
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        Object conversationId = response.context().get(ChatMemory.CONVERSATION_ID);

        if (response.chatResponse() == null) {
            log.warn("[audit] 응답 없음 conversationId={}", conversationId);
            return response;
        }

        List<Generation> results = response.chatResponse().getResults();
        int toolCalls = results.stream()
                .filter(g -> g.getOutput() != null && g.getOutput().hasToolCalls())
                .mapToInt(g -> g.getOutput().getToolCalls().size())
                .sum();

        log.info("[audit] 응답 conversationId={} 생성수={} 도구호출={}건 finishReason={}",
                conversationId, results.size(), toolCalls,
                results.isEmpty() ? "-" : results.getFirst().getMetadata().getFinishReason());

        return response;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
