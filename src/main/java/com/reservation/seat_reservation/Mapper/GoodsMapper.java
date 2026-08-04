package com.reservation.seat_reservation.Mapper;

import com.reservation.seat_reservation.Dto.GoodsResponseDto;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface GoodsMapper {

    List<GoodsResponseDto> getAllGoods();
    GoodsResponseDto getOneGoods(Long goodsIdx);
    // 비관적 락 적용 조회
    GoodsResponseDto getOneGoodsWithPessimisticLock(Long goodsIdx);
    int buyGoods(Long goodsIdx, Long memberIdx, LocalDateTime orderDate);
}
