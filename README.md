#  상품 선점 및 결제 시스템 (동시성 제어)

> **트래픽이 집중되는 상품 선점 시 발생하는 재고 동시성 문제(Race Condition)를 비관적 락(Pessimistic Lock)을 통해 해결하고 데이터 무결성을 보장하는 프로젝트입니다.**

---

##  1. 프로젝트 개요
* **개발 기간**: 2026.07 ~ 진행 중
* **주요 목표**:
    * 트래픽 폭주 상황에서 1개 또는 한정된 수량의 상품에 대해 수백 명의 유저가 동시에 선점을 시도할 때 발생하는 **재고 초과 차감(Over-selling)** 문제 해결
    * 선점(결제 대기) $\rightarrow$ PG 결제 승인 $\rightarrow$ 결제 완료 및 재고 차감 확정으로 이어지는 실무형 결제 라이프사이클 구현
    * `ExecutorService`와 `CountDownLatch`를 활용한 100개 멀티스레드 동시성 검증 자동화

---

##  2. 기술 스택 (Tech Stack)

### Backend
- **Language**: Java 17
- **Framework**: Spring Boot 3.x
- **ORM / Data Access**: MyBatis 3.5
- **Database**: PostgreSQL
- **Test**: JUnit 5, AssertJ

---

##  3. 아키텍처 및 DB 설계

###  데이터베이스 ERD 구조 (`order_info` & `goods`)
```sql
-- 1. 상품 테이블 (goods)
CREATE TABLE reservation.goods (
    idx           SERIAL PRIMARY KEY,
    quantity      INT NOT NULL DEFAULT 1,              -- 수량 (1개만 남은 상황 테스트용)
    goods_name    VARCHAR(100) NOT NULL,               -- 상품명
    price         INT NOT NULL DEFAULT 0,              -- 가격
    category_idx  INT NOT NULL REFERENCES reservation.category(idx), -- 카테고리 외래키
    description   TEXT NULL,                           -- 상품 상세 설명
    registe_date  TIMESTAMP NOT NULL DEFAULT NOW(),     -- 상품 등록 일시
    create_date TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 2. 주문/선점 이력 테이블 (order_info)
CREATE TABLE reservation.order_info (
    idx                  SERIAL PRIMARY KEY,
    merchant_uid         VARCHAR(50)  NOT NULL UNIQUE, --  [고유 주문번호] 결제식별용 (예: ORD20260727-A1B2C3)
    goods_idx            INT          NOT NULL REFERENCES reservation.goods(idx),
    member_idx           INT          NOT NULL REFERENCES reservation.member(idx),
    address              VARCHAR(255) NOT NULL,
    order_date           TIMESTAMP    NOT NULL DEFAULT NOW(),   -- 주문/선점 시작 시각
    shipment             CHAR(1)      NOT NULL DEFAULT 'N',     -- 'Y':발송완료, 'N':미발송
    iscancel             CHAR(1)      NOT NULL DEFAULT 'N',     -- 'Y':취소, 'N':정상
    order_status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING' -- PENDING, PAID, EXPIRED, CANCELLED
);

-- 3. 카테고리 테이블 (category)
CREATE TABLE reservation.category (
    idx           SERIAL PRIMARY KEY,                    -- 카테고리 PK (1, 2, 3...)
    category_name VARCHAR(50) NOT NULL UNIQUE,          -- 카테고리 이름 (예: 전자기기)
    create_date TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 4. 회원 테이블 (member)
CREATE TABLE reservation.member (
		idx SERIAL PRIMARY KEY,
		member_id VARCHAR(50) NOT NULL UNIQUE,
		member_pw VARCHAR(255),
		member_name VARCHAR(100),
		member_nickname VARCHAR(100),
		member_phone_number VARCHAR(20),
		member_gender VARCHAR(1), -- 'M':남자, 'W':여자
		is_active CHAR(1) NOT NULL DEFAULT 'Y', -- 'Y':활성, 'N':탈퇴, 'H':휴면계정
		create_date TIMESTAMP NOT NULL DEFAULT NOW()
);
```
##  4. 주문 및 결제 플로우 (Life Cycle)

1. **[선점 요청] `POST /api/goods/reserve`**
    - DB에서 상품 수량(`quantity > 0`)을 검증한 후 수량을 1 차감합니다.
    - 외부 노출용 고유 주문번호(`merchant_uid`)를 채번하여 `order_info` 테이블에 `PENDING`(결제 대기) 상태로 주문 이력을 생성합니다.
2. **[PG 결제 진입]**
    - 백엔드는 생성된 `merchant_uid` 및 `orderIdx`를 프론트엔드로 반환합니다.
    - 프론트엔드는 전달받은 `merchant_uid`를 활용하여 카카오페이/토스페이먼츠 등 PG사 결제 모듈을 호출합니다.
3. **[결제 완료 승인] `POST /api/orders/complete`**
    - PG사 결제 성공 수신 시 프론트엔드가 결제 완료 API를 호출합니다.
    - 백엔드는 `merchant_uid` 및 `idx`(PK) 이중 검증을 거쳐 주문 상태를 `'PAID'`로 변경합니다. (외부 PG사 연동은 API 승인 수신 모킹으로 처리)

---

##  5. 동시성 제어 해결 전략 (Pragmatic Approach)

###  발생 가능 문제 (Race Condition)
- 수량이 **1개** 남아있는 한정판 상품에 **100명의 유저가 동일한 시각(ms)에 동시에 선점을 요청**하는 상황을 가정합니다.
- 동시성 제어(Lock)가 없는 환경에서는 100개의 스레드가 동시에 `SELECT quantity FROM goods WHERE idx = 1`을 실행합니다.
- 모든 스레드가 수량이 1개 남아있다고 판단하여 각각 `quantity - 1` UPDATE 및 주문 조회를 성공 처리하여, **100명 모두 선점에 성공하는 치명적인 재고 붕괴(Over-selling)** 현상이 발생합니다.

###  해결책: 비관적 락 (Pessimistic Lock - `FOR UPDATE`)
- **배타적 락(X-Lock) 획득**: 상품 조회 시 `SELECT ... FOR UPDATE` 쿼리를 실행하여 가장 먼저 도착한 트랜잭션이 해당 상품 Row의 X-Lock을 점유합니다.
- **순차 처리 및 안전한 거부**: 후속 요청 스레드들은 이전 트랜잭션이 커밋(`COMMIT`)되어 락을 해제할 때까지 DB 수준에서 대기(Lock Wait)합니다. 락을 획득한 후 수량이 `0`임을 확인하면 차례대로 선점 실패 예외(`IllegalStateException`)를 발생시키며 안전하게 요청을 거부합니다.

---

##  6. 동시성 제어 검증 및 테스트 결과 (진행 예정)

- [ ] **[2단계] 락 미적용 상태 테스트**: `ExecutorService`와 `CountDownLatch`를 활용해 100개 스레드 동시 요청 시 재고 초과 차감(Race Condition) 발생 증명
- [ ] **[3단계] 비관적 락 적용 상태 테스트**: 100개 스레드 동시 요청 중 정확히 1명만 선점 성공, 나머지 99명 실패 및 수량 `0` 유지 검증
