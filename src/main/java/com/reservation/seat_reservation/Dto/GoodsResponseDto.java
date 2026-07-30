package com.reservation.seat_reservation.Dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GoodsResponseDto {

    private Long idx;               // 상품 PK
    private String merchantUid = "";       // 고유 주문 번호
    private String goodsName;       // 상품명
    private Integer price;          // 가격
    private Integer quantity;       // 수량
    private String categoryName;    // 카테고리명
}
