package com.reservation.seat_reservation.Dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class paymentCompleteRequestDto {
    private Long orderIdx; // 주문 idx
    private String merchantUid; // 고유 주문번호
    private String address; // 최종 입력된 배송지 주소

}
