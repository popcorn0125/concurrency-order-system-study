package com.reservation.seat_reservation.Mapper;

import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

@Mapper
public interface OrderMapper {
    public Long setOrderPending(String orderNumStr, Long goodsIdx, Long memberIdx, String address, LocalDateTime orderDate);
    public int updateToPaid(Long orderIdx, String merchantUid, String address);
}
