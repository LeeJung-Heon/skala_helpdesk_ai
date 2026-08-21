package com.example.lab3.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.example.lab3.repository.OrderRepository;

/**
 * Phase 4 — 주문 조회 도구.
 *
 * <p>문서로 답할 수 없는 것(실시간 주문 상태)을 도구로 가져온다.
 *
 * <p><b>소유자 검증은 도구 안에서 한다.</b> 모델이 넘긴 {@code orderId} 는
 * 사용자의 것이 아닐 수 있다. 사용자 ID 는 인자로 받지 않고
 * {@link ToolContext} 에서만 꺼낸다 — 모델이 개입할 수 없는 경로다.
 */
@Component
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);

    private final OrderRepository orders;
    private final ToolCallTracker tracker;

    public OrderTools(OrderRepository orders, ToolCallTracker tracker) {
        this.tracker = tracker;
        this.orders = orders;
    }

    @Tool(description = """
            주문번호로 배송 상태와 예상 도착일을 조회한다.
            사용자가 특정 주문의 현재 상태·배송 위치·도착 예정일을 물을 때 사용한다.
            사내 규정이나 정책 질문에는 사용하지 않는다.
            """)
    String orderStatus(
            @ToolParam(description = "조회할 주문번호. 예: 12345") String orderId,
            ToolContext context) {

        tracker.record(context);
        String userId = ToolUsers.require(context);
        log.info("[tool] orderStatus orderId={} userId={}", orderId, userId);

        return orders.findOwned(orderId, userId)
                .map(o -> "주문 %s · 상품 %s · 상태 %s · 예상도착 %s"
                        .formatted(o.id(), o.item(), o.status(), o.eta()))
                .orElse("해당 주문을 찾을 수 없습니다.");
    }

    @Tool(description = """
            사용자 본인의 최근 주문 목록을 최신순으로 조회한다.
            주문번호를 모른 채 "내 주문", "최근에 뭐 샀지"처럼 물을 때 사용한다.
            """)
    String myOrders(ToolContext context) {
        tracker.record(context);
        String userId = ToolUsers.require(context);
        log.info("[tool] myOrders userId={}", userId);

        var list = orders.findAllOwned(userId);
        if (list.isEmpty()) {
            return "주문 내역이 없습니다.";
        }
        return list.stream()
                .map(o -> "주문 %s · %s · %s".formatted(o.id(), o.item(), o.status()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("주문 내역이 없습니다.");
    }
}
