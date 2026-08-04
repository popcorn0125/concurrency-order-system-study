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

## 5. 동시성 제어 해결 전략 (Pragmatic Approach)

### 발생 가능 문제 (Race Condition & DB 과부하)
- 수량이 **1개** 남아있는 상품에 **100명의 유저가 동일한 시각(ms)에 동시에 선점(결제 대기)을 요청**하는 상황을 가정합니다.
- 동시성 제어(Lock)가 없는 환경에서는 여러 스레드가 동시에 `SELECT quantity FROM goods WHERE idx = 1`을 통과하여 최신이 아닌 데이터인 `quantity = 1`을 들고 비즈니스 로직으로 일제히 진입합니다.
- `UPDATE ... WHERE quantity > 0` 조건절 덕분에 재고가 음수로 떨어지는 초과 차감은 방지되나, **실패할 스레드들까지 DB `UPDATE` 쿼리로 몰려들어 불필요한 DB 락 경합 및 커넥션 리소스 낭비를 유발**하게 됩니다.
- 특히 로직 순서나 외래키 관계에 따라 **주문서(`order_info`)만 100장 발행되거나 오버부킹이 터지는 치명적인 Race Condition 위험**을 내포합니다.

---

### 해결책: 비관적 락 (Pessimistic Lock - `SELECT ... FOR UPDATE`)
- **배타적 락(X-Lock) 점유**: 상품 조회 단계부터 `SELECT ... FOR UPDATE` 쿼리를 실행하여 가장 먼저 도착한 1번 트랜잭션이 해당 상품 Row의 X-Lock을 점유합니다.
- **조회 단계에서의 순차 대기 및 즉시 차단**:
    - 후속 요청 스레드들은 앞선 트랜잭션이 커밋(`COMMIT`)될 때까지 DB 수준에서 대기(Lock Wait)합니다.
    - 1번 유저가 재고를 `0`으로 차감하고 나면, 대기하던 후속 스레드들은 조회를 할 때 이미 `quantity = 0`인 최신 상태를 확인하게 됩니다.
    - 이를 통해 **불필요한 DB `UPDATE` 쿼리를 실행해 보지도 않고, `SELECT` 단계에서 즉시 품절 예외(`IllegalStateException`)를 내뱉으며 안전하게 차단**됩니다.
---

## 6. 동시성 제어 검증 및 테스트 결과 (Concurrency Test Result)

본 시스템의 동시성 제어 유무에 따른 안정성과 성능 변화를 검증하기 위해 **JUnit 5, `ExecutorService`(100개 스레드 풀), `CountDownLatch`**를 활용하여 동일 시점(ms) 100건 동시 요청 통합 테스트를 수행하였습니다.

---

### 100건 동시 요청 테스트 결과 수치 비교

| 구분 | 2단계: 락 미적용 (No Lock) | 3단계: 비관적 락 적용 (Pessimistic Lock) |
| :--- | :---: | :---: |
| **요청 스레드 수** | **100명** (동시 접속) | **100명** (동시 접속) |
| **성공 / 실패 건수** | **성공 1건 / 실패 99건** | **성공 1건 / 실패 99건** |
| **최종 남아있는 재고** | **0개** | **0개 (데이터 무결성 보장)** |
| **동시 진입 방식** | **복수 스레드 `SELECT` 동시 통과** (Race Condition) | **단 1개 스레드만 진입** (후속 대기) |
| **실패 스레드 동작** | **DB `UPDATE` 쿼리까지 진행 후 실패** (`Updates: 0`) | **`SELECT` 단계에서 최신재고 확인 후 즉시 차단** |
| **처리 메커니즘** | 단일 `UPDATE` 조건절(`WHERE quantity > 0`)에 의존 | `SELECT ... FOR UPDATE` (Row X-Lock 점유) |
| **총 소요 시간 (ms)** | 약 150 ms ~ 180 ms | **204 ms (Lock Wait 대기시간 발생)** |

---

### 항목별 상세 검증 및 결과 분석

#### 1. 2단계: 락 미적용 상태 (No Lock)
- **문제점**: `SELECT` 조회 단계에서 락이 존재하지 않아, 100개의 스레드가 동시에 `quantity = 1` 상태의 최신이 아닌 데이터(Stale Data)를 읽고 비즈니스 로직으로 일제히 진입합니다.
- **결과**: `UPDATE` 조건절에 의존하여 재고가 음수로 내려가는 것은 방지하였으나, 조회 단계에서 모든 스레드가 통과하여 DB에 불필요한 UPDATE 락 경합 및 트래픽 폭주가 발생했습니다.

#### 2. 3단계: 비관적 락 적용 상태 (`SELECT ... FOR UPDATE`)
- **개선점**: 상품 조회 시 DB 수준에서 **배타적 락(X-Lock)**을 점유하여, 선두 1번 스레드가 커밋될 때까지 후속 99개 스레드는 조회 단계에서 **대기(Lock Wait)** 상태로 차단됩니다.
- **소요 시간 증가 이유 (204ms)**:
    - 병렬 처리(Parallel) 방식에서 **줄 서기(Sequential Order)** 구조로 전환됨에 따라 앞선 트랜잭션의 처리를 대기하는 시간(Lock Wait)이 추가되었습니다.
    - 약 **0.02초~0.05초 수준의 미미한 대기시간(Trade-off)**을 대가로, 한정판 선점 시스템의 **가장 중요한 가치인 데이터 무결성(Race Condition 차단)을 100% 달성**했습니다.