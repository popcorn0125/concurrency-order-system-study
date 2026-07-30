package com.reservation.seat_reservation.Controller;

import com.reservation.seat_reservation.Dto.GoodsReserveRequestDto;
import com.reservation.seat_reservation.Dto.GoodsResponseDto;
import com.reservation.seat_reservation.Dto.paymentCompleteRequestDto;
import com.reservation.seat_reservation.Service.GoodsService;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;


@RestController
@RequestMapping("/goods")
@RequiredArgsConstructor
public class GoodsController {


    private final GoodsService goodsService;

    // 상품 목록 조회
    @GetMapping("")
    public ResponseEntity<List<GoodsResponseDto>> getAllGoods() {
        List<GoodsResponseDto> list = goodsService.getAllGoods();
        return ResponseEntity.ok(list);
    }

    // 특정 상품 상세 조회
    @GetMapping("/{goodsDetail}")
    public ResponseEntity<GoodsResponseDto> getOneGoods(@PathVariable("goodsDetail") Long goodsIdx) {
        return ResponseEntity.ok(goodsService.getOneGoods(goodsIdx));
    }

    // 상품 구매
    @PostMapping("/buy")
    public ResponseEntity<?> buyGoods(@RequestBody GoodsReserveRequestDto params) {
        System.out.println("GoodsController - buyGoods 실행");

        return ResponseEntity.ok(goodsService.buyGoods(params));
    }

    // 결제 성공 시 호출하는 api. 결제 대기 -> 결제완료로 변경하기 위해 사용
    @PostMapping("/paymentComplete")
    public ResponseEntity<String> paymentComplete(@RequestBody paymentCompleteRequestDto params) {
        System.out.println("GoodsController - paymentComplete 실행");
        goodsService.paymentComplete(params);
        return ResponseEntity.ok("결제가 성공적으로 완료되었습니다.");
    }

}
