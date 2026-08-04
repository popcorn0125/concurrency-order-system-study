package com.reservation.seat_reservation.service;

import com.reservation.seat_reservation.Dto.GoodsReserveRequestDto;
import com.reservation.seat_reservation.Dto.GoodsResponseDto;
import com.reservation.seat_reservation.Mapper.GoodsMapper;
import com.reservation.seat_reservation.Service.GoodsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class GoodsServiceConcurrencyTest {

    @Autowired
    private GoodsService goodsService;

    @Autowired
    private GoodsMapper goodsMapper;

    static class DummyUser {
        final Long memberIdx;
        final String address;

        DummyUser(Long memberIdx, String address) {
            this.memberIdx = memberIdx;
            this.address = address;
        }
    }

    @Test
    @DisplayName("수량이 1개인 상품에 100개 동시 요청 시 인한 초과 차감 발생 증명 및 실행 시간 측정")
    void reserveGoodsConcurrencyNoLockTest() throws InterruptedException {
        // GIVEN : 테스트 조건 설정
        Long goodsIdx = 1L; // 테스트할 상품 PK
        int threadCount = 100; // 동시에 요청을 날릴 스레드(유저) 수

        List<DummyUser> userList = new ArrayList<>();
        for (int i = 1; i <= threadCount; i++) {
            userList.add(new DummyUser((long) i, "서울특별시 " + i + "번지 아파트"));
        }

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount); // 100개 스레드 풀 생성

        // 스레드들이 동시에 실행될 수 있게 제어하는 Latch
        CountDownLatch readyLatch = new CountDownLatch(threadCount); // 100개의 스레드가 준비될 때까지 대기
        CountDownLatch startLatch = new CountDownLatch(1); // 0이 되면 100개 동시 출발
        CountDownLatch finishLatch = new CountDownLatch(threadCount); // 100개 스레드가 모두 종료될 때까지 메인 스레드 대기

        // 성공 및 실패 횟수를 멀티스레드 안전하게 카운팅하는 객체
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // WHEN : 100개의 스레드가 동시에 선점(결제 대기) 요청을 보냄
        for (int i = 0; i < threadCount; i++) {
            final DummyUser user = userList.get(i);
            executorService.submit(() -> {
                try {
                    readyLatch.countDown(); // "스레드 준비 끝났음!" 신호 보내기
                    startLatch.await();     // 메인 스레드가 startLatch.countDown()를 할때까지 대기

                    // 실제 락이 적용되지 않은 선점 로직 실행
                    GoodsReserveRequestDto requestDto = new GoodsReserveRequestDto();
                    requestDto.setGoodsIdx(goodsIdx);
                    requestDto.setMemberIdx(user.memberIdx);
                    requestDto.setAddress(user.address);

                    goodsService.buyGoods(requestDto);
                    successCount.incrementAndGet(); // 성공시 카운트 +1
                } catch (Exception e) {
                    failCount.incrementAndGet(); // 실패시 카운트 +1
                    e.printStackTrace(); // 이 줄을 추가해서 어떤 예외가 터지는지 확인해 주세요!
                } finally {
                    finishLatch.countDown(); // "스레드 작업 완료했음" 신호 보내기
                }
            });
        }

        readyLatch.await(); //100개 스레드가 모두 생성되고 준비될 때까지 대기

        // [시간 측정 시작] DB 동시 요청 처리 직전부터 측정
        long startTime = System.currentTimeMillis();

        startLatch.countDown(); // 시작! 100개 스레드가 동시에 db요청
        finishLatch.await(); // 100개 스레드의 모든 작업이 끝날 때까지 메인 스레드가 대기함

        // [시간 측정 종료] 100개 스레드 작업 완료 직후 측정
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime; // 소요 시간 (ms)

        //THEN : 결과 검증 (수량 차감 발생 확인)
        System.out.println("==========================================");
        System.out.println("요청한 스레드 수: " + threadCount);
        System.out.println("성공한 요청 수: " + successCount.get());
        System.out.println("실패한 요청 수: " + failCount.get());
        System.out.println("총 동시성 소요 시간: " + duration + " ms (" + (duration / 1000.0) + "초)");

        GoodsResponseDto goodsDetail = goodsService.getOneGoods(goodsIdx);
        System.out.println("최종 남은 재고 수량: " + goodsDetail.getQuantity());
        System.out.println("==========================================");

        assertThat(successCount.get()).isGreaterThan(1); // 1개 초과 성공했음을 검증 (버그 증명)
    }

    @Test
    @DisplayName("10,000명 대규모 동시 요청 테스트")
    void reserveGoodsConcurrency10KTest() throws InterruptedException {
        // GIVEN : 테스트 조건 설정
        Long goodsIdx = 1L;
        int totalRequests = 10000; // 총 요청할 유저 수 (10,000명)
        int poolSize = 200;        // OS가 안전하게 감당할 수 있는 실제 스레드 풀 크기

        List<DummyUser> userList = new ArrayList<>();
        for (int i = 1; i <= totalRequests; i++) {
            userList.add(new DummyUser((long) i, "서울특별시 " + i + "번지 아파트"));
        }

        // OS 스레드는 200개만 생성하여 10,000개 작업을 번갈아가며 처리
        ExecutorService executorService = Executors.newFixedThreadPool(poolSize);

        // 10,000개의 작업이 모두 완료될 때까지 대기하는 Latch
        CountDownLatch finishLatch = new CountDownLatch(totalRequests);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // [시간 측정 시작]
        long startTime = System.currentTimeMillis();

        // WHEN : 10,000개의 선점(결제 대기) 요청을 스레드 풀에 제출
        for (int i = 0; i < totalRequests; i++) {
            final DummyUser user = userList.get(i);
            executorService.submit(() -> {
                try {
                    GoodsReserveRequestDto requestDto = new GoodsReserveRequestDto();
                    requestDto.setGoodsIdx(goodsIdx);
                    requestDto.setMemberIdx(user.memberIdx);
                    requestDto.setAddress(user.address);

                    goodsService.buyGoods(requestDto); // 또는 buyGoods
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    finishLatch.countDown(); // 작업 완료 시 카운트 차감
                }
            });
        }

        finishLatch.await(); // 10,000개 작업이 모두 완료될 때까지 대기

        // [시간 측정 종료]
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        executorService.shutdown(); // 스레드 풀 종료

        // THEN : 결과 검증 및 출력
        System.out.println("==========================================");
        System.out.println("총 요청 수: " + totalRequests + "건");
        System.out.println("성공한 요청 수: " + successCount.get() + "건");
        System.out.println("실패한 요청 수: " + failCount.get() + "건");
        System.out.println("총 소요 시간: " + duration + " ms (" + (duration / 1000.0) + "초)");

        GoodsResponseDto goodsDetail = goodsService.getOneGoods(goodsIdx);
        System.out.println("최종 남은 재고 수량: " + goodsDetail.getQuantity());
        System.out.println("==========================================");
    }
}
