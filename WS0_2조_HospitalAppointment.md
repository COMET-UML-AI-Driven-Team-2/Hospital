# WS0_2조_HospitalAppointment_pmt

## 개발 요청

아래는 병원 예약 키오스크 시스템의 Problem Description입니다.
이를 바탕으로 **순수 Java**(외부 프레임워크 금지)로 동작하는 시스템을 **단일 `.java` 파일**로 구현해 주세요. UI 없이 콘솔 출력으로 동작을 확인합니다.

---

## 시스템 개요

병원은 외래 진료과와 분원에 지리적으로 분산된 **복수의 셀프서비스 예약 키오스크**를 운영한다. 각 키오스크는 WAN을 통해 **중앙 Hospital Server**에 연결되어 있다.

이 시스템의 본질은 **분산된 다수의 키오스크 단말**과 **하나의 중앙 서버** 사이의 클라이언트-서버 구조이며, 데이터와 비즈니스 로직(인증, 스케줄 검증, 예약 제한 등)은 모두 서버 측에 집중되어 있다.

---

## 키오스크 하드웨어 구성

각 키오스크는 다음 4개의 하드웨어 장치를 갖는다. 이 장치들은 단순한 입출력 수단이 아니라 시스템과 외부 세계의 **경계(boundary)**를 형성하는 요소이다.

| 장치 | 역할 |
|---|---|
| Card Reader | 환자 신분증·보험카드 인증 |
| Keyboard / Display | 사용자 입력 및 정보 출력 |
| Barcode Scanner | 예약 확인증 바코드 스캔 |
| Receipt Printer | 예약 확인증·접수증 출력 |

---

## Actor 및 역할

| Actor | 구분 | 역할 |
|---|---|---|
| Patient | 사람 | 키오스크를 통해 예약, 취소/변경, 접수, 조회 수행 |
| Hospital Operator | 사람 | 키오스크 Start Up / Shut Down 수행 |
| Card Reader | 장치 | 환자 카드 정보를 시스템에 전달 |
| Barcode Scanner | 장치 | 예약 바코드 정보를 시스템에 전달 |

Patient와 Hospital Operator는 **서로 다른 권한과 사용 흐름**을 갖는다. Patient는 인증 후 진료 관련 서비스를 이용하고, Operator는 키오스크 단말 자체의 운영 상태를 제어한다.

---

## 핵심 기능 (Use Case)

| Use Case | 정상 처리 조건 |
|---|---|
| Make Appointment | 인증 성공, 요청 시간대 가용, 중복 예약 없음, 미납금 없음 |
| Cancel Appointment | 인증 성공, 해당 예약이 해당 환자 소유, 취소 가능 시간 내 |
| Reschedule Appointment | 인증 성공, 변경 요청 시간대 가용, 중복 없음 |
| Check In | 인증 성공, 당일 예약 존재, 접수 가능 시간 내 |
| Query Appointment Status | 인증 성공, 유효한 예약 존재 |
| Start Up Terminal | Hospital Operator 인증 성공, 단말이 Offline 상태 |
| Shut Down Terminal | Hospital Operator 인증 성공, 단말이 Idle 상태 |

7개 Use Case 모두 **인증을 전제**로 하며, 인증 주체가 Patient인지 Operator인지에 따라 이후 흐름이 갈린다.

---

## 대안 흐름 (거부·예외 조건)

정상 흐름만큼이나 대안 흐름이 시스템의 핵심이다. 아래 조건들은 단순 에러 처리가 아니라, 서버가 비즈니스 규칙을 적용하여 요청을 거부하는 상황이다.

| 대안 흐름 | 적용 Use Case | 처리 방향 |
|---|---|---|
| 요청 시간대 만석 | Make Appointment | 예약 거부, 다른 시간대 안내 |
| 중복 예약 존재 | Make Appointment / Reschedule | 거부, 기존 예약 확인 안내 |
| 미납금 존재 | Make Appointment | 거부, 수납 안내 출력 |
| 취소 불가 시간 초과 | Cancel Appointment | 거부, 전화 취소 안내 |
| 환자 카드 인증 실패 | 전체 UC | 재시도 요청 (최대 N회 후 세션 종료) |
| 접수 가능 시간 외 | Check In | 거부, 접수 가능 시간 안내 |
| 거래 취소 | 전체 UC | 확인 전 취소 시 Idle 상태 복귀 |

특히 **"확인 전 언제든 거래 취소 가능"**이라는 조건은 모든 Use Case에 공통으로 적용되며, 키오스크 상태를 Idle로 되돌려야 한다.

---

## Hospital Server의 책임

서버는 단순 데이터 저장소가 아니라, 다음과 같은 **비즈니스 로직의 실행 주체**이다.

- 환자 인증(credential validation) 처리
- 의사 가용성 확인 및 스케줄링 규칙 적용
- 환자별 예약 제한(appointment limits per patient) 관리
- 환자, 의사, 예약, 진료 기록 데이터의 유지·관리

---

## 데이터 관리 위치

모든 핵심 데이터는 Hospital Server에서 중앙 관리된다. 키오스크 단말에는 데이터가 저장되지 않는다.

| 데이터 | 관리 위치 |
|---|---|
| 환자 정보 (Patient records) | Hospital Server |
| 의사·스케줄 정보 (Doctor / Schedule records) | Hospital Server |
| 예약 이력 (Appointment records) | Hospital Server |
| 미납금 정보 (Bill records) | Hospital Server |
| 환자 인증 처리 | Hospital Server |

---

## 동시성 상황

이 시스템에서 가장 까다로운 부분이다. 복수의 키오스크가 **동시에** 동일한 자원에 접근할 수 있다.

- **동일 의사 스케줄 동시 접근**: 두 키오스크가 같은 의사의 같은 시간대에 동시에 예약을 시도할 수 있으므로, 중복 예약을 방지해야 한다.
- **동일 환자 레코드 동시 접근**: 예약 건수 및 미납금 정보의 정합성을 보장해야 한다.

---

## 키오스크 운영 상태

키오스크는 다음 운영 상태를 거쳐 전이한다:

**Idle → Authenticating → Selecting Service → Processing Request → Completing 또는 Cancelling**

이 상태들은 키오스크 한 대의 **세션 생명주기**를 나타내며, Operator에 의한 Start Up(→Idle)과 Shut Down(Idle→Offline)이 이 생명주기의 시작과 끝을 제어한다.

---

위 Problem Description의 모든 요소를 반영하여 구현해 주세요.
클래스 구조, 책임 분배, 데이터 모델, 동시성 처리 방식 등 설계 결정은 자유롭게 판단하여 구성하세요.