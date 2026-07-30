package com.reservation.seat_reservation.Dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
public class OrderResponseDto {
    private Long orderIdx; // 주문정보 idx
    private String orderNum; // 고유 주문번호
    private Integer price; // 가격
    private String goodsName; // 제품 이름
    private LocalDateTime orderDate; // 주문 날짜
    private String message;
}
