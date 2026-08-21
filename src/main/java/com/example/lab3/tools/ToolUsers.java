package com.example.lab3.tools;

import org.springframework.ai.chat.model.ToolContext;

/**
 * 도구가 사용자 신원을 꺼내는 <b>단 하나의 통로</b>.
 *
 * <p>사용자 ID 는 모델이 넘긴 인자가 아니라 {@link ToolContext} 에서만 온다.
 * 모델이 준 값을 신뢰하면 "다른 사람 주문 번호를 넣어 달라"는 요청이 그대로 통한다.
 */
public final class ToolUsers {

    /** ToolContext 에 사용자 ID 를 담는 키 */
    public static final String USER_ID = "userId";

    private ToolUsers() {
    }

    /**
     * @throws IllegalStateException 컨텍스트에 사용자 ID 가 없으면 — 조용히 넘어가지 않는다
     */
    public static String require(ToolContext context) {
        Object userId = context == null ? null : context.getContext().get(USER_ID);
        if (userId == null) {
            throw new IllegalStateException(
                    "ToolContext 에 userId 가 없다. 서비스가 toolContext 를 넘기지 않았다.");
        }
        return userId.toString();
    }
}
