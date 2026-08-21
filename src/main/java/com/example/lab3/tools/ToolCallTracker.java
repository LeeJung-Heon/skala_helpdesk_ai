package com.example.lab3.tools;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * 이번 대화 턴에서 도구가 몇 번 실행됐는지 센다.
 *
 * <p><b>응답 객체로는 알 수 없다.</b> 도구 루프가 끝나고 돌아온 마지막 응답에는
 * 도구 호출 흔적이 남아 있지 않아, {@code chatResponse} 를 뒤지면 실제로 도구가
 * 돌았는데도 언제나 {@code false} 가 나온다.
 *
 * <p>스트리밍은 도구 실행 스레드와 구독 스레드가 갈릴 수 있어 ThreadLocal 을 쓰지 않는다.
 * {@code conversationId} 를 키로 센다.
 */
@Component
public class ToolCallTracker {

    public static final String CONVERSATION_ID = "conversationId";

    private final ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();

    /** 요청 시작 시 호출 — 이전 턴의 카운트가 남지 않게 한다. */
    public void begin(String conversationId) {
        counts.put(conversationId, 0);
    }

    /** 도구가 실행될 때마다 호출한다. */
    public void record(ToolContext context) {
        String id = idOf(context);
        if (id == null || id.isBlank()) {
            return;
        }
        counts.merge(id, 1, Integer::sum);
    }

    public int count(String conversationId) {
        return counts.getOrDefault(conversationId, 0);
    }

    private static String idOf(ToolContext context) {
        if (context == null) {
            return null;
        }
        Object raw = context.getContext().get(CONVERSATION_ID);
        return raw == null ? null : raw.toString();
    }
}
