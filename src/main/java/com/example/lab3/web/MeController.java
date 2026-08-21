package com.example.lab3.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.lab3.domain.Order;
import com.example.lab3.domain.Ticket;
import com.example.lab3.repository.OrderRepository;
import com.example.lab3.repository.TicketRepository;

/**
 * 사용자 본인의 <b>현재 상태</b>를 한 번에 내려준다 — 주문 내역과 접수한 티켓.
 *
 * <h2>왜 별도 API 인가</h2>
 * 같은 데이터를 도구({@code myOrders})로도 볼 수 있지만, 그건 <b>모델이 부를 때만</b> 온다.
 * 화면 옆에 상태를 늘 띄워 두려면 모델을 거치지 않는 직접 경로가 필요하다.
 * 토큰도 지연도 들지 않고, 무엇보다 <b>모델이 요약하지 않은 원본</b>이 보인다.
 *
 * <h2>소유자 격리</h2>
 * 도구와 같은 규칙을 쓴다 — 사용자 ID 는 요청 헤더에서만 오고,
 * 조회는 항상 소유자 조건이 걸린 {@code findAllOwned} 로 한다.
 * 운영이라면 이 헤더는 인증 토큰에서 채워야 한다(Phase 7 범위).
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final OrderRepository orders;
    private final TicketRepository tickets;

    public MeController(OrderRepository orders, TicketRepository tickets) {
        this.orders = orders;
        this.tickets = tickets;
    }

    /** 화면 오른쪽 상태 패널이 그리는 데이터 */
    public record StateDto(String userId, List<Order> orders, List<TicketDto> tickets) {}

    /**
     * 티켓 표시용 — {@code LocalDateTime} 을 문자열로 굳혀 보낸다.
     *
     * <p>화면은 정렬만 하면 되고 시간대 해석을 할 필요가 없다.
     */
    public record TicketDto(String no, String orderId, String type,
                            String reason, String status, String createdAt) {}

    @GetMapping("/state")
    public StateDto state(@RequestHeader(name = "X-User-Id", defaultValue = "user1") String userId) {
        List<TicketDto> ticketDtos = tickets.findAllOwned(userId).stream()
                .map(t -> new TicketDto(t.no(), t.orderId(), t.type(), t.reason(),
                        t.status(), t.createdAt().toString()))
                .toList();

        return new StateDto(userId, orders.findAllOwned(userId), ticketDtos);
    }
}
