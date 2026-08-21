package com.example.lab3.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Repository;

import com.example.lab3.domain.Ticket;

/**
 * Phase 4 — 티켓 접수.
 *
 * <p>접수만 한다. 승인·처리는 별도 절차이며 이 저장소가 하는 일이 아니다.
 */
@Repository
public class TicketRepository {

    private final Map<String, Ticket> store = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(1000);

    /** 티켓을 접수한다 — 상태는 항상 승인 대기로 시작한다. */
    public Ticket request(String orderId, String ownerId, String type, String reason) {
        String no = "TK-" + sequence.incrementAndGet();
        Ticket ticket = new Ticket(no, orderId, ownerId, type, reason,
                Ticket.PENDING_APPROVAL, LocalDateTime.now());
        store.put(no, ticket);
        return ticket;
    }

    public List<Ticket> findAllOwned(String ownerId) {
        return store.values().stream()
                .filter(t -> t.ownerId().equals(ownerId))
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
    }
}
