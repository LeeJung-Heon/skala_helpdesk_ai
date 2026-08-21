package com.example.lab3.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.example.lab3.domain.Ticket;
import com.example.lab3.repository.OrderRepository;
import com.example.lab3.repository.TicketRepository;

/**
 * Phase 4 — 티켓 접수 도구(쓰기).
 *
 * <p>읽기 도구와 달리 <b>상태를 바꾼다</b>. 그래서 두 가지 통제가 붙는다.
 * <ol>
 *   <li><b>소유자 검증</b> — 남의 주문으로 티켓을 열 수 없다</li>
 *   <li><b>승인 게이트</b> — 접수만 되고, 실제 처리는 담당자 승인 후다</li>
 * </ol>
 *
 * <p>도구 설명에 "승인 후 처리된다"를 명시한 것은 모델이 사용자에게
 * "환불해 드렸습니다"라고 단정하지 않게 하기 위해서다.
 */
@Component
public class TicketTools {

    private static final Logger log = LoggerFactory.getLogger(TicketTools.class);

    private final TicketRepository tickets;
    private final OrderRepository orders;
    private final ToolCallTracker tracker;

    public TicketTools(TicketRepository tickets, OrderRepository orders, ToolCallTracker tracker) {
        this.tracker = tracker;
        this.tickets = tickets;
        this.orders = orders;
    }

    @Tool(description = """
            교환 또는 환불 티켓을 접수한다. 접수만 되며 실제 처리는 담당자 승인 후 진행된다.
            사용자가 명시적으로 교환·반품·환불을 요청할 때만 사용한다.
            규정을 묻기만 하는 경우에는 사용하지 않는다.
            """)
    String createTicket(
            @ToolParam(description = "대상 주문번호. 예: 12345") String orderId,
            @ToolParam(description = "티켓 종류. EXCHANGE(교환) 또는 REFUND(환불) 중 하나") String type,
            @ToolParam(description = "사용자가 밝힌 사유. 없으면 '사유 미기재'") String reason,
            ToolContext context) {

        tracker.record(context);
        String userId = ToolUsers.require(context);
        log.info("[tool] createTicket orderId={} type={} userId={}", orderId, type, userId);

        // ① 소유자 검증 — 도구 안에서. 모델이 넘긴 orderId 를 믿지 않는다.
        if (orders.findOwned(orderId, userId).isEmpty()) {
            return "해당 주문을 찾을 수 없어 티켓을 접수하지 못했습니다.";
        }

        String normalized = "REFUND".equalsIgnoreCase(type) ? "REFUND" : "EXCHANGE";
        String safeReason = (reason == null || reason.isBlank()) ? "사유 미기재" : reason;

        // ② 승인 게이트 — 접수 상태로만 생성된다
        Ticket ticket = tickets.request(orderId, userId, normalized, safeReason);

        return "티켓 %s 를 접수했습니다. 담당자 승인 후 처리됩니다.".formatted(ticket.no());
    }
}
