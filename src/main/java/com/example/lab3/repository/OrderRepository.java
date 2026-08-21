package com.example.lab3.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import com.example.lab3.domain.Order;

/**
 * Phase 4 — 주문 데이터 접근.
 *
 * <p>실습 범위를 AI 조립에 두기 위해 인메모리로 둔다.
 * JPA 로 바꿔도 <b>이 인터페이스만 지키면</b> 상위 계층은 그대로다 —
 * 특히 {@link #findOwned} 의 "소유자 조건이 쿼리에 들어간다"는 성질이 핵심이다.
 */
@Repository
public class OrderRepository {

    private final Map<String, Order> store = new ConcurrentHashMap<>();

    public OrderRepository() {
        save(new Order("12345", "user1", "무선 이어폰", "배송중",
                LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 21)));
        save(new Order("12346", "user1", "USB-C 케이블", "배송완료",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 4)));
        save(new Order("12347", "user1", "기계식 키보드", "상품준비중",
                LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 22)));
        save(new Order("99999", "user2", "노트북 스탠드", "결제완료",
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23)));
    }

    public void save(Order order) {
        store.put(order.id(), order);
    }

    /**
     * <b>소유자 조건이 함께 걸린 조회.</b>
     *
     * <p>{@code findById} 를 만들지 않은 것은 의도적이다 —
     * 존재하면 소유자 검증을 잊고 부를 위험이 생긴다.
     */
    public Optional<Order> findOwned(String orderId, String ownerId) {
        return Optional.ofNullable(store.get(orderId))
                .filter(o -> o.ownerId().equals(ownerId));
    }

    public List<Order> findAllOwned(String ownerId) {
        return store.values().stream()
                .filter(o -> o.ownerId().equals(ownerId))
                .sorted((a, b) -> b.orderedAt().compareTo(a.orderedAt()))
                .toList();
    }
}
