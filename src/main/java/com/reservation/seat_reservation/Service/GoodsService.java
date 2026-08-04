package com.reservation.seat_reservation.Service;

import com.reservation.seat_reservation.Dto.GoodsReserveRequestDto;
import com.reservation.seat_reservation.Dto.GoodsResponseDto;
import com.reservation.seat_reservation.Dto.OrderResponseDto;
import com.reservation.seat_reservation.Dto.paymentCompleteRequestDto;
import com.reservation.seat_reservation.Mapper.GoodsMapper;
import com.reservation.seat_reservation.Mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GoodsService {

    private final GoodsMapper goodsMapper;
    private final OrderMapper orderMapper;

    public List<GoodsResponseDto> getAllGoods() {
        return goodsMapper.getAllGoods();
    }

    public GoodsResponseDto getOneGoods(Long goodsIdx) {
        GoodsResponseDto result = goodsMapper.getOneGoods(goodsIdx);
        if(result == null){
            throw new IllegalArgumentException("존재하지 않는 상품입니다.");
        }
        return result;
    }

    @Transactional
    public OrderResponseDto buyGoods(GoodsReserveRequestDto requestDto) {
        // 상품 조회
//         GoodsResponseDto goods = goodsMapper.getOneGoods(requestDto.getGoodsIdx());

        // 비관적 락(select ~ for update) 쿼리로 조회
        GoodsResponseDto goods = goodsMapper.getOneGoodsWithPessimisticLock(requestDto.getGoodsIdx());

        if(goods == null) {
            throw new IllegalArgumentException("존재하지 않는 상품입니다. ");
        }

        // 상품 수량 및 판매 상태 확인
        if(goods.getQuantity() <= 0 ) {
            throw new IllegalStateException("이미 품절된 상품입니다.");
        }

        // 상품 구매
        LocalDateTime now = LocalDateTime.now();
        int updateRow = goodsMapper.buyGoods(
                requestDto.getGoodsIdx(),
                requestDto.getMemberIdx(),
                now
        );

        if(updateRow == 0) {
            throw new IllegalStateException("다른분이 먼저 결제를 하여 품절된 상품입니다. 다시 확인해주세요.");
        }

        // 고유 주문번호 생성 (예: ORD20260727-A1B2C3D4)
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String uuidStr = UUID.randomUUID().toString().substring(0,8).toUpperCase();
        String orderNumStr = "ORD" + dateStr + "-" + uuidStr;

        // order_info 주문 대기 이력 생성(주문서에 결제 상태만 대기로 진행)
        Long idx = orderMapper.setOrderPending(
                orderNumStr,
                requestDto.getGoodsIdx(),
                requestDto.getMemberIdx(),
                requestDto.getAddress(),
                now
        );

        return OrderResponseDto.builder()
                .orderIdx(idx)
                .goodsName(goods.getGoodsName())
                .orderNum(goods.getGoodsName())
                .price(goods.getPrice())
                .orderNum(orderNumStr)
                .orderDate(now)
                .message("선점 성공, 결제 대기")
                .build();
    }

    // 결제 완료 처리 서비스 로직
    @Transactional
    public void paymentComplete(paymentCompleteRequestDto requestDto) {
        int updateRow = orderMapper.updateToPaid(
                requestDto.getOrderIdx(),
                requestDto.getMerchantUid(),
                requestDto.getAddress()
        );

        // 만약 업데이트 된 항목이 없다면 ( 잘못된 주문번호 이거나 결제 시간 5분 만료)
        if(updateRow == 0) {
            throw new IllegalStateException("유효하지 않거나 이미 만료/완료된 주문입니다. 주문번호 : " + requestDto.getMerchantUid());
        }
    }
}
