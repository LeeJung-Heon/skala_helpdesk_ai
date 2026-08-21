package com.example.lab3.domain;

import java.time.LocalDateTime;

/**
 * 교환·환불 티켓.
 *
 * <p>접수 즉시 처리되지 않는다 — {@code status} 는 항상 {@code PENDING_APPROVAL} 로 시작한다.
 * 모델이 도구를 불렀다고 해서 환불이 실행되면 안 되기 때문이다(승인 게이트).
 */
public record Ticket(
        String no,
        String orderId,
        String ownerId,
        String type,
        String reason,
        String status,
        LocalDateTime createdAt) {

    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
}
