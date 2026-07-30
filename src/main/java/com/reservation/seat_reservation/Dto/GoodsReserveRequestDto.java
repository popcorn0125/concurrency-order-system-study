package com.reservation.seat_reservation.Dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class GoodsReserveRequestDto {
    private String merchantUid; // 고유 전화번호
    private Long goodsIdx;
    private Long memberIdx;
    private String address;
}
