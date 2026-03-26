# Hospital Appointment Kiosk System

> **COMET UML AI-Driven Team 2** | WS0 Workshop Project

병원 외래 진료 **셀프서비스 예약 키오스크** 시스템을 순수 Java로 구현한 프로젝트입니다.

---

## Architecture

```
┌──────────┐  WAN   ┌─────────────────┐
│ KIOSK-A  │◄──────►│                 │
└──────────┘        │  Hospital       │
                    │  Server         │
┌──────────┐  WAN   │  (Central)      │
│ KIOSK-B  │◄──────►│                 │
└──────────┘        └─────────────────┘
                      │ Patients DB
                      │ Doctors / Schedule DB
                      │ Appointments DB
                      │ Bills DB
```

- **Client-Server 구조** : 복수의 키오스크 단말이 하나의 중앙 서버에 연결
- **비즈니스 로직 서버 집중** : 인증, 스케줄 검증, 예약 제한, 미납금 확인 등 모든 규칙은 서버 측에서 처리
- **키오스크는 입출력 전담** : 하드웨어 장치를 통한 사용자 인터페이스 역할만 수행

---

## Hardware Components

| Device | Interface | Role |
|---|---|---|
| Card Reader | `CardReader` | 환자 신분증/보험카드 인증 |
| Keyboard / Display | `KeyboardDisplay` | 사용자 입력 및 정보 출력 |
| Barcode Scanner | `BarcodeScanner` | 예약 확인증 바코드 스캔 |
| Receipt Printer | `ReceiptPrinter` | 예약 확인증/접수증 출력 |

---

## Use Cases

### Normal Flows

| Use Case | Description |
|---|---|
| **Make Appointment** | 환자 인증 → 의사/시간 선택 → 예약 생성 → 확인증 출력 |
| **Cancel Appointment** | 환자 인증 → 예약 선택 → 취소 처리 |
| **Reschedule Appointment** | 환자 인증 → 예약 선택 → 새 시간대 변경 → 변경 확인증 출력 |
| **Check In** | 환자 인증 → 당일 예약 접수 |
| **Query Appointment Status** | 환자 인증 → 전체 예약 현황 조회 → 내역 출력 |
| **Start Up Terminal** | Operator 인증 → 키오스크 Offline → Idle |
| **Shut Down Terminal** | Operator 인증 → 키오스크 Idle → Offline |

### Alternative Flows (Rejection)

| Scenario | Result |
|---|---|
| 요청 시간대 만석 | 예약 거부 + 대안 시간대 안내 |
| 중복 예약 존재 | 거부 + 기존 예약 확인 안내 |
| 미납금 존재 | 거부 + 수납 안내 출력 |
| 취소 가능 시간 초과 (2시간 이내) | 거부 + 전화 취소 안내 |
| 카드 인증 실패 | 최대 3회 재시도 후 세션 종료 |
| 접수 가능 시간 외 | 거부 + 접수 가능 시간 안내 |
| 거래 취소 | 언제든 취소 가능 → Idle 복귀 |

---

## Concurrency

복수의 키오스크가 **동시에 동일 자원**에 접근하는 상황을 처리합니다.

- **Doctor Slot Lock** : 같은 의사, 같은 시간대 동시 예약 시 1건만 성공
- **Patient Lock** : 환자별 예약 건수/미납금 정합성 보장
- **Deadlock Prevention** : `identityHashCode` 기반 일관된 락 순서 적용

---

## Terminal State Machine

```
         startUp()              Card Auth           Select Service
OFFLINE ──────────► IDLE ──────────────► AUTHENTICATING ──────────► SELECTING_SERVICE
   ▲                 ▲                                                     │
   │   shutDown()    │                                                     ▼
   │                 │              cancelTransaction()            PROCESSING_REQUEST
   │                 ◄──────────────────────────────────────┐              │
   │                 │              CANCELLING ◄────────────┘       ┌──────┴──────┐
   │                 │                                              ▼             ▼
   │                 ◄───────────────────────────────── COMPLETING    CANCELLING
   │                                                                      │
   │                 ◄────────────────────────────────────────────────────┘
```

---

## Project Structure

```
Hospital/
├── README.md
└── week01/
    ├── WS0_2조_HospitalAppointment.java    # 전체 소스코드 (단일 파일)
    ├── WS0_2조_HospitalAppointment.md      # 프롬프트 요구사항 문서
    └── output.txt                           # 콘솔 실행 결과
```

---

## How to Run

```bash
# Compile
javac WS0_2조_HospitalAppointment.java

# Run
java -Dfile.encoding=UTF-8 HospitalAppointmentSystem
```

> **Requirements** : JDK 21+ | No external dependencies

---

## Demo Scenarios

실행 시 아래 시나리오가 순차적으로 시연됩니다:

1. **Operator Start Up** — 두 키오스크(A, B) 가동
2. **Make Appointment Success** — P1001 환자 예약 성공 + 확인증 출력
3. **Outstanding Bill Rejection** — P1002 미납금으로 예약 거부
4. **Query Status** — P1001 예약 현황 조회
5. **Concurrent Booking Test** — 두 키오스크 동시 예약 → 1건만 성공
6. **Reschedule Success** — 예약 시간 변경 + 변경 확인증 출력
7. **Cancel Too Late Rejection** — 2시간 이내 취소 거부
8. **Check In Success** — 당일 예약 접수 성공
9. **Check In Outside Window** — 당일 아닌 예약 접수 거부
10. **Barcode Scan Query** — 바코드로 예약 정보 조회
11. **Transaction Cancel** — 진행 중 거래 취소 → Idle 복귀
12. **Operator Shut Down** — 두 키오스크 종료

---

## Team

**COMET UML AI-Driven Team 2** — WS0 Workshop

