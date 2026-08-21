package com.example.lab3.domain;

import java.time.LocalDate;

/**
 * 주문. {@code ownerId} 는 소유자 검증의 근거가 되므로 반드시 함께 다룬다.
 */
public record Order(
        String id,
        String ownerId,
        String item,
        String status,
        LocalDate orderedAt,
        LocalDate eta) {
}
