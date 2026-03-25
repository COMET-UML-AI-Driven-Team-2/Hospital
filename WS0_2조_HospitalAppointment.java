import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * WS0_2조_HospitalAppointment_pmt
 *
 * 단일 Java 파일로 구현한 병원 예약 키오스크 시스템 예제.
 * - 순수 Java
 * - 콘솔 기반 동작 확인
 * - 중앙 HospitalServer에 데이터/비즈니스 로직 집중
 * - 복수 키오스크/동시성 반영
 * - 하드웨어 장치(CardReader, KeyboardDisplay, BarcodeScanner, ReceiptPrinter) 포함
 */
public class HospitalAppointmentSystem {

    /* =========================================================
     * Entry Point
     * ========================================================= */
    public static void main(String[] args) {
        DemoScenario.run();
    }

    /* =========================================================
     * Demo Scenario
     * ========================================================= */
    static class DemoScenario {
        static void run() {
            HospitalServer server = SampleDataFactory.createServerWithSampleData();

            KioskTerminal kioskA = new KioskTerminal(
                    "KIOSK-A",
                    server,
                    new SimpleCardReader(),
                    new SimpleKeyboardDisplay(),
                    new SimpleBarcodeScanner(),
                    new SimpleReceiptPrinter()
            );

            KioskTerminal kioskB = new KioskTerminal(
                    "KIOSK-B",
                    server,
                    new SimpleCardReader(),
                    new SimpleKeyboardDisplay(),
                    new SimpleBarcodeScanner(),
                    new SimpleReceiptPrinter()
            );

            System.out.println("\n================= OPERATOR START UP =================");
            kioskA.startUp("op-001", "admin123");
            kioskB.startUp("op-001", "admin123");

            LocalDate today = LocalDate.now();
            LocalDateTime slot1 = LocalDateTime.of(today.plusDays(1), LocalTime.of(10, 0));
            LocalDateTime slot2 = LocalDateTime.of(today.plusDays(1), LocalTime.of(11, 0));
            LocalDateTime slotCheckIn = LocalDateTime.of(today, LocalTime.now().plusMinutes(60).withSecond(0).withNano(0));

            System.out.println("\n================= MAKE APPOINTMENT SUCCESS =================");
            kioskA.makeAppointment(
                    new CardData("P1001", "900101-1111111", "H-1001"),
                    "D001",
                    slot1
            );

            System.out.println("\n================= OUTSTANDING BILL REJECTION =================");
            kioskA.makeAppointment(
                    new CardData("P1002", "920202-2222222", "H-1002"),
                    "D001",
                    slot2
            );

            System.out.println("\n================= QUERY STATUS =================");
            kioskA.queryAppointmentStatus(new CardData("P1001", "900101-1111111", "H-1001"));

            System.out.println("\n================= CONCURRENT BOOKING TEST =================");
            runConcurrentBookingTest(server, kioskA, kioskB, today.plusDays(2));

            System.out.println("\n================= RESCHEDULE SUCCESS =================");
            String apptIdForReschedule = server.findFirstAppointmentIdByPatient("P1001");
            kioskA.rescheduleAppointment(
                    new CardData("P1001", "900101-1111111", "H-1001"),
                    apptIdForReschedule,
                    LocalDateTime.of(today.plusDays(1), LocalTime.of(15, 0))
            );

            System.out.println("\n================= CANCEL TOO LATE REJECTION =================");
            String todayAppointmentId = server.createForceAppointmentForDemo("P1001", "D002", slotCheckIn);
            kioskA.cancelAppointment(
                    new CardData("P1001", "900101-1111111", "H-1001"),
                    todayAppointmentId
            );

            System.out.println("\n================= CHECK IN SUCCESS =================");
            String todayCheckinId = server.createForceAppointmentForDemo(
                    "P1003",
                    "D002",
                    LocalDateTime.of(LocalDate.now(), LocalTime.now().plusMinutes(30).withSecond(0).withNano(0))
            );
            kioskB.checkIn(new CardData("P1003", "930303-3333333", "H-1003"), todayCheckinId);

            System.out.println("\n================= CHECK IN OUTSIDE WINDOW =================");
            String tomorrowAppointmentId = server.createForceAppointmentForDemo(
                    "P1001",
                    "D002",
                    LocalDateTime.of(today.plusDays(1), LocalTime.of(10, 0))
            );
            kioskB.checkIn(new CardData("P1001", "900101-1111111", "H-1001"), tomorrowAppointmentId);

            System.out.println("\n================= BARCODE CHECK / QUERY =================");
            kioskA.scanReceiptAndShow(todayCheckinId);

            System.out.println("\n================= TRANSACTION CANCEL EXAMPLE =================");
            kioskA.beginPatientSession(new CardData("P1001", "900101-1111111", "H-1001"));
            kioskA.cancelTransaction();

            System.out.println("\n================= OPERATOR SHUT DOWN =================");
            kioskA.shutDown("op-001", "admin123");
            kioskB.shutDown("op-001", "admin123");
        }

        static void runConcurrentBookingTest(HospitalServer server, KioskTerminal kioskA, KioskTerminal kioskB, LocalDate date) {
            LocalDateTime sameSlot = LocalDateTime.of(date, LocalTime.of(9, 0));

            Runnable r1 = () -> kioskA.makeAppointment(
                    new CardData("P1001", "900101-1111111", "H-1001"),
                    "D003",
                    sameSlot
            );
            Runnable r2 = () -> kioskB.makeAppointment(
                    new CardData("P1003", "930303-3333333", "H-1003"),
                    "D003",
                    sameSlot
            );

            Thread t1 = new Thread(r1, "BOOK-THREAD-1");
            Thread t2 = new Thread(r2, "BOOK-THREAD-2");
            t1.start();
            t2.start();
            try {
                t1.join();
                t2.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            System.out.println("[SERVER] D003 @ " + sameSlot + " 예약 현황 => " + server.getAppointmentsByDoctorAndTime("D003", sameSlot).size() + "건");
            for (Appointment a : server.getAppointmentsByDoctorAndTime("D003", sameSlot)) {
                System.out.println("  - " + a);
            }
        }
    }

    /* =========================================================
     * Enums
     * ========================================================= */
    enum TerminalState {
        OFFLINE,
        IDLE,
        AUTHENTICATING,
        SELECTING_SERVICE,
        PROCESSING_REQUEST,
        COMPLETING,
        CANCELLING
    }

    enum AppointmentStatus {
        SCHEDULED,
        CHECKED_IN,
        CANCELLED
    }

    enum ServiceType {
        MAKE_APPOINTMENT,
        CANCEL_APPOINTMENT,
        RESCHEDULE_APPOINTMENT,
        CHECK_IN,
        QUERY_APPOINTMENT_STATUS,
        START_UP_TERMINAL,
        SHUT_DOWN_TERMINAL
    }

    enum ResultCode {
        SUCCESS,
        AUTH_FAILED,
        INVALID_OPERATOR,
        TERMINAL_NOT_OFFLINE,
        TERMINAL_NOT_IDLE,
        TERMINAL_OFFLINE,
        SLOT_FULL,
        DUPLICATE_APPOINTMENT,
        OUTSTANDING_BILLS,
        APPOINTMENT_NOT_FOUND,
        APPOINTMENT_OWNER_MISMATCH,
        CANCELLATION_TIME_EXCEEDED,
        INVALID_CHECKIN_WINDOW,
        NO_TODAY_APPOINTMENT,
        BUSINESS_RULE_REJECTED,
        USER_CANCELLED,
        MAX_AUTH_RETRY_EXCEEDED,
        INVALID_STATE,
        SYSTEM_ERROR
    }

    /* =========================================================
     * Generic Result Wrapper
     * ========================================================= */
    static class OperationResult<T> {
        private final boolean success;
        private final ResultCode code;
        private final String message;
        private final T data;
        private final List<String> suggestions;

        private OperationResult(boolean success, ResultCode code, String message, T data, List<String> suggestions) {
            this.success = success;
            this.code = code;
            this.message = message;
            this.data = data;
            this.suggestions = suggestions == null ? Collections.emptyList() : suggestions;
        }

        static <T> OperationResult<T> ok(String message, T data) {
            return new OperationResult<>(true, ResultCode.SUCCESS, message, data, Collections.emptyList());
        }

        static <T> OperationResult<T> fail(ResultCode code, String message) {
            return new OperationResult<>(false, code, message, null, Collections.emptyList());
        }

        static <T> OperationResult<T> fail(ResultCode code, String message, List<String> suggestions) {
            return new OperationResult<>(false, code, message, null, suggestions);
        }

        boolean isSuccess() { return success; }
        ResultCode getCode() { return code; }
        String getMessage() { return message; }
        T getData() { return data; }
        List<String> getSuggestions() { return suggestions; }

        @Override
        public String toString() {
            return "OperationResult{" +
                    "success=" + success +
                    ", code=" + code +
                    ", message='" + message + '\'' +
                    ", data=" + data +
                    ", suggestions=" + suggestions +
                    '}';
        }
    }

    /* =========================================================
     * Domain Models
     * ========================================================= */
    static class Patient {
        private final String patientId;
        private final String name;
        private final String residentNumber;
        private final String insuranceCardNo;
        private final int appointmentLimit;

        Patient(String patientId, String name, String residentNumber, String insuranceCardNo, int appointmentLimit) {
            this.patientId = patientId;
            this.name = name;
            this.residentNumber = residentNumber;
            this.insuranceCardNo = insuranceCardNo;
            this.appointmentLimit = appointmentLimit;
        }

        String getPatientId() { return patientId; }
        String getName() { return name; }
        String getResidentNumber() { return residentNumber; }
        String getInsuranceCardNo() { return insuranceCardNo; }
        int getAppointmentLimit() { return appointmentLimit; }

        @Override
        public String toString() {
            return "Patient{" +
                    "patientId='" + patientId + '\'' +
                    ", name='" + name + '\'' +
                    '}';
        }
    }

    static class Operator {
        private final String operatorId;
        private final String name;
        private final String password;

        Operator(String operatorId, String name, String password) {
            this.operatorId = operatorId;
            this.name = name;
            this.password = password;
        }

        String getOperatorId() { return operatorId; }
        String getName() { return name; }
        String getPassword() { return password; }
    }

    static class Doctor {
        private final String doctorId;
        private final String name;
        private final String department;
        private final int slotCapacity;

        Doctor(String doctorId, String name, String department, int slotCapacity) {
            this.doctorId = doctorId;
            this.name = name;
            this.department = department;
            this.slotCapacity = slotCapacity;
        }

        String getDoctorId() { return doctorId; }
        String getName() { return name; }
        String getDepartment() { return department; }
        int getSlotCapacity() { return slotCapacity; }

        @Override
        public String toString() {
            return department + " / " + name + "(" + doctorId + ")";
        }
    }

    static class Bill {
        private final String billId;
        private final String patientId;
        private final int amount;
        private volatile boolean paid;

        Bill(String billId, String patientId, int amount, boolean paid) {
            this.billId = billId;
            this.patientId = patientId;
            this.amount = amount;
            this.paid = paid;
        }

        String getBillId() { return billId; }
        String getPatientId() { return patientId; }
        int getAmount() { return amount; }
        boolean isPaid() { return paid; }
        void pay() { this.paid = true; }

        @Override
        public String toString() {
            return "Bill{" + billId + ", patientId=" + patientId + ", amount=" + amount + ", paid=" + paid + "}";
        }
    }

    static class MedicalRecord {
        private final String recordId;
        private final String patientId;
        private final List<String> notes;

        MedicalRecord(String recordId, String patientId, List<String> notes) {
            this.recordId = recordId;
            this.patientId = patientId;
            this.notes = new ArrayList<>(notes);
        }

        String getRecordId() { return recordId; }
        String getPatientId() { return patientId; }
        List<String> getNotes() { return Collections.unmodifiableList(notes); }
    }

    static class Appointment {
        private final String appointmentId;
        private final String patientId;
        private final String doctorId;
        private volatile LocalDateTime appointmentTime;
        private volatile AppointmentStatus status;
        private final LocalDateTime createdAt;
        private volatile LocalDateTime updatedAt;

        Appointment(String appointmentId, String patientId, String doctorId, LocalDateTime appointmentTime) {
            this.appointmentId = appointmentId;
            this.patientId = patientId;
            this.doctorId = doctorId;
            this.appointmentTime = appointmentTime;
            this.status = AppointmentStatus.SCHEDULED;
            this.createdAt = LocalDateTime.now();
            this.updatedAt = this.createdAt;
        }

        String getAppointmentId() { return appointmentId; }
        String getPatientId() { return patientId; }
        String getDoctorId() { return doctorId; }
        LocalDateTime getAppointmentTime() { return appointmentTime; }
        AppointmentStatus getStatus() { return status; }
        LocalDateTime getCreatedAt() { return createdAt; }
        LocalDateTime getUpdatedAt() { return updatedAt; }

        void reschedule(LocalDateTime newTime) {
            this.appointmentTime = newTime;
            this.updatedAt = LocalDateTime.now();
        }

        void cancel() {
            this.status = AppointmentStatus.CANCELLED;
            this.updatedAt = LocalDateTime.now();
        }

        void checkIn() {
            this.status = AppointmentStatus.CHECKED_IN;
            this.updatedAt = LocalDateTime.now();
        }

        boolean isActive() {
            return status == AppointmentStatus.SCHEDULED || status == AppointmentStatus.CHECKED_IN;
        }

        @Override
        public String toString() {
            return "Appointment{" +
                    "appointmentId='" + appointmentId + '\'' +
                    ", patientId='" + patientId + '\'' +
                    ", doctorId='" + doctorId + '\'' +
                    ", appointmentTime=" + appointmentTime +
                    ", status=" + status +
                    '}';
        }
    }

    static class CardData {
        private final String patientId;
        private final String residentNumber;
        private final String insuranceCardNo;

        CardData(String patientId, String residentNumber, String insuranceCardNo) {
            this.patientId = patientId;
            this.residentNumber = residentNumber;
            this.insuranceCardNo = insuranceCardNo;
        }

        String getPatientId() { return patientId; }
        String getResidentNumber() { return residentNumber; }
        String getInsuranceCardNo() { return insuranceCardNo; }

        @Override
        public String toString() {
            return "CardData{patientId='" + patientId + "'}";
        }
    }

    /* =========================================================
     * Request Models
     * ========================================================= */
    static class MakeAppointmentRequest {
        private final String patientId;
        private final String doctorId;
        private final LocalDateTime desiredTime;

        MakeAppointmentRequest(String patientId, String doctorId, LocalDateTime desiredTime) {
            this.patientId = patientId;
            this.doctorId = doctorId;
            this.desiredTime = desiredTime;
        }
    }

    static class CancelAppointmentRequest {
        private final String patientId;
        private final String appointmentId;

        CancelAppointmentRequest(String patientId, String appointmentId) {
            this.patientId = patientId;
            this.appointmentId = appointmentId;
        }
    }

    static class RescheduleAppointmentRequest {
        private final String patientId;
        private final String appointmentId;
        private final LocalDateTime newTime;

        RescheduleAppointmentRequest(String patientId, String appointmentId, LocalDateTime newTime) {
            this.patientId = patientId;
            this.appointmentId = appointmentId;
            this.newTime = newTime;
        }
    }

    static class CheckInRequest {
        private final String patientId;
        private final String appointmentId;

        CheckInRequest(String patientId, String appointmentId) {
            this.patientId = patientId;
            this.appointmentId = appointmentId;
        }
    }

    /* =========================================================
     * Hardware Boundary Interfaces
     * ========================================================= */
    interface CardReader {
        CardData readCard(CardData input);
    }

    interface KeyboardDisplay {
        void showMessage(String terminalId, String message);
        void showMenu(String terminalId, List<String> options);
    }

    interface BarcodeScanner {
        String scanBarcode(String barcode);
    }

    interface ReceiptPrinter {
        void print(String terminalId, String content);
    }

    /* =========================================================
     * Hardware Implementations
     * ========================================================= */
    static class SimpleCardReader implements CardReader {
        @Override
        public CardData readCard(CardData input) {
            System.out.println("[CardReader] card read => " + input);
            return input;
        }
    }

    static class SimpleKeyboardDisplay implements KeyboardDisplay {
        @Override
        public void showMessage(String terminalId, String message) {
            System.out.println("[Display-" + terminalId + "] " + message);
        }

        @Override
        public void showMenu(String terminalId, List<String> options) {
            System.out.println("[Display-" + terminalId + "] 메뉴 선택 가능: " + options);
        }
    }

    static class SimpleBarcodeScanner implements BarcodeScanner {
        @Override
        public String scanBarcode(String barcode) {
            System.out.println("[BarcodeScanner] scanned => " + barcode);
            return barcode;
        }
    }

    static class SimpleReceiptPrinter implements ReceiptPrinter {
        @Override
        public void print(String terminalId, String content) {
            System.out.println("[Printer-" + terminalId + "]\n" + content + "\n");
        }
    }

    /* =========================================================
     * Central Server
     * ========================================================= */
    static class HospitalServer {
        private final Map<String, Patient> patients = new ConcurrentHashMap<>();
        private final Map<String, Operator> operators = new ConcurrentHashMap<>();
        private final Map<String, Doctor> doctors = new ConcurrentHashMap<>();
        private final Map<String, Appointment> appointments = new ConcurrentHashMap<>();
        private final Map<String, Bill> bills = new ConcurrentHashMap<>();
        private final Map<String, MedicalRecord> medicalRecords = new ConcurrentHashMap<>();

        private final Map<String, ReentrantLock> doctorSlotLocks = new ConcurrentHashMap<>();
        private final Map<String, ReentrantLock> patientLocks = new ConcurrentHashMap<>();
        private final AtomicInteger appointmentSequence = new AtomicInteger(1000);

        private static final Duration CANCELLATION_DEADLINE = Duration.ofHours(2);
        private static final Duration CHECK_IN_OPEN_BEFORE = Duration.ofMinutes(60);
        private static final Duration CHECK_IN_CLOSE_AFTER = Duration.ofMinutes(30);

        void addPatient(Patient patient) { patients.put(patient.getPatientId(), patient); }
        void addOperator(Operator operator) { operators.put(operator.getOperatorId(), operator); }
        void addDoctor(Doctor doctor) { doctors.put(doctor.getDoctorId(), doctor); }
        void addBill(Bill bill) { bills.put(bill.getBillId(), bill); }
        void addMedicalRecord(MedicalRecord record) { medicalRecords.put(record.getRecordId(), record); }
        void addAppointment(Appointment appointment) { appointments.put(appointment.getAppointmentId(), appointment); }

        OperationResult<Patient> authenticatePatient(CardData cardData) {
            if (cardData == null || cardData.getPatientId() == null) {
                return OperationResult.fail(ResultCode.AUTH_FAILED, "카드 정보가 비어 있습니다.");
            }
            Patient patient = patients.get(cardData.getPatientId());
            if (patient == null) {
                return OperationResult.fail(ResultCode.AUTH_FAILED, "등록되지 않은 환자입니다.");
            }
            boolean matched = Objects.equals(patient.getResidentNumber(), cardData.getResidentNumber())
                    && Objects.equals(patient.getInsuranceCardNo(), cardData.getInsuranceCardNo());
            if (!matched) {
                return OperationResult.fail(ResultCode.AUTH_FAILED, "환자 카드 인증에 실패했습니다.");
            }
            return OperationResult.ok("환자 인증 성공", patient);
        }

        OperationResult<Operator> authenticateOperator(String operatorId, String password) {
            Operator operator = operators.get(operatorId);
            if (operator == null || !Objects.equals(operator.getPassword(), password)) {
                return OperationResult.fail(ResultCode.INVALID_OPERATOR, "운영자 인증 실패");
            }
            return OperationResult.ok("운영자 인증 성공", operator);
        }

        OperationResult<Appointment> makeAppointment(MakeAppointmentRequest request) {
            Patient patient = patients.get(request.patientId);
            Doctor doctor = doctors.get(request.doctorId);
            if (patient == null || doctor == null) {
                return OperationResult.fail(ResultCode.BUSINESS_RULE_REJECTED, "환자 또는 의사 정보가 유효하지 않습니다.");
            }

            String slotKey = buildDoctorSlotKey(request.doctorId, request.desiredTime);
            ReentrantLock patientLock = patientLocks.computeIfAbsent(request.patientId, k -> new ReentrantLock());
            ReentrantLock slotLock = doctorSlotLocks.computeIfAbsent(slotKey, k -> new ReentrantLock());

            lockBoth(patientLock, slotLock);
            try {
                if (hasOutstandingBills(request.patientId)) {
                    return OperationResult.fail(
                            ResultCode.OUTSTANDING_BILLS,
                            "미납금이 존재하여 예약할 수 없습니다.",
                            Collections.singletonList("수납 창구 또는 온라인 수납을 이용하세요.")
                    );
                }

                if (countActiveAppointmentsByPatient(request.patientId) >= patient.getAppointmentLimit()) {
                    return OperationResult.fail(ResultCode.BUSINESS_RULE_REJECTED, "환자별 예약 가능 건수를 초과했습니다.");
                }

                if (hasOverlappingAppointment(request.patientId, request.desiredTime, null)) {
                    return OperationResult.fail(
                            ResultCode.DUPLICATE_APPOINTMENT,
                            "동일 시간대의 기존 예약이 존재합니다.",
                            Collections.singletonList("기존 예약 현황을 확인하세요.")
                    );
                }

                long currentBooked = appointments.values().stream()
                        .filter(a -> a.getStatus() == AppointmentStatus.SCHEDULED)
                        .filter(a -> Objects.equals(a.getDoctorId(), request.doctorId))
                        .filter(a -> Objects.equals(a.getAppointmentTime(), request.desiredTime))
                        .count();

                if (currentBooked >= doctor.getSlotCapacity()) {
                    return OperationResult.fail(
                            ResultCode.SLOT_FULL,
                            "요청 시간대가 이미 만석입니다.",
                            suggestAlternativeSlots(request.doctorId, request.desiredTime)
                    );
                }

                String appointmentId = "APT-" + appointmentSequence.incrementAndGet();
                Appointment appointment = new Appointment(appointmentId, request.patientId, request.doctorId, request.desiredTime);
                appointments.put(appointmentId, appointment);
                return OperationResult.ok("예약이 완료되었습니다.", appointment);
            } finally {
                unlockBoth(patientLock, slotLock);
            }
        }

        OperationResult<Appointment> cancelAppointment(CancelAppointmentRequest request) {
            Appointment appointment = appointments.get(request.appointmentId);
            if (appointment == null) {
                return OperationResult.fail(ResultCode.APPOINTMENT_NOT_FOUND, "예약을 찾을 수 없습니다.");
            }
            if (!Objects.equals(appointment.getPatientId(), request.patientId)) {
                return OperationResult.fail(ResultCode.APPOINTMENT_OWNER_MISMATCH, "해당 환자의 예약이 아닙니다.");
            }

            ReentrantLock patientLock = patientLocks.computeIfAbsent(request.patientId, k -> new ReentrantLock());
            ReentrantLock slotLock = doctorSlotLocks.computeIfAbsent(buildDoctorSlotKey(appointment.getDoctorId(), appointment.getAppointmentTime()), k -> new ReentrantLock());
            lockBoth(patientLock, slotLock);
            try {
                if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
                    return OperationResult.fail(ResultCode.BUSINESS_RULE_REJECTED, "취소 가능한 예약 상태가 아닙니다.");
                }
                Duration remaining = Duration.between(LocalDateTime.now(), appointment.getAppointmentTime());
                if (remaining.compareTo(CANCELLATION_DEADLINE) < 0) {
                    return OperationResult.fail(
                            ResultCode.CANCELLATION_TIME_EXCEEDED,
                            "취소 가능 시간을 초과했습니다.",
                            Collections.singletonList("병원으로 전화하여 취소를 진행하세요.")
                    );
                }
                appointment.cancel();
                return OperationResult.ok("예약이 취소되었습니다.", appointment);
            } finally {
                unlockBoth(patientLock, slotLock);
            }
        }

        OperationResult<Appointment> rescheduleAppointment(RescheduleAppointmentRequest request) {
            Appointment appointment = appointments.get(request.appointmentId);
            if (appointment == null) {
                return OperationResult.fail(ResultCode.APPOINTMENT_NOT_FOUND, "변경할 예약을 찾을 수 없습니다.");
            }
            if (!Objects.equals(appointment.getPatientId(), request.patientId)) {
                return OperationResult.fail(ResultCode.APPOINTMENT_OWNER_MISMATCH, "해당 환자의 예약이 아닙니다.");
            }
            if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
                return OperationResult.fail(ResultCode.BUSINESS_RULE_REJECTED, "변경 가능한 예약 상태가 아닙니다.");
            }

            String oldSlotKey = buildDoctorSlotKey(appointment.getDoctorId(), appointment.getAppointmentTime());
            String newSlotKey = buildDoctorSlotKey(appointment.getDoctorId(), request.newTime);
            ReentrantLock patientLock = patientLocks.computeIfAbsent(request.patientId, k -> new ReentrantLock());
            ReentrantLock oldLock = doctorSlotLocks.computeIfAbsent(oldSlotKey, k -> new ReentrantLock());
            ReentrantLock newLock = doctorSlotLocks.computeIfAbsent(newSlotKey, k -> new ReentrantLock());

            List<ReentrantLock> locks = Arrays.asList(patientLock, oldLock, newLock);
            locks.sort(Comparator.comparingInt(System::identityHashCode));
            for (ReentrantLock lock : locks) lock.lock();
            try {
                if (hasOverlappingAppointment(request.patientId, request.newTime, appointment.getAppointmentId())) {
                    return OperationResult.fail(
                            ResultCode.DUPLICATE_APPOINTMENT,
                            "변경 요청 시간대에 중복 예약이 존재합니다.",
                            Collections.singletonList("기존 예약 시간을 확인하세요.")
                    );
                }

                Doctor doctor = doctors.get(appointment.getDoctorId());
                long currentBooked = appointments.values().stream()
                        .filter(a -> a.getStatus() == AppointmentStatus.SCHEDULED)
                        .filter(a -> !Objects.equals(a.getAppointmentId(), appointment.getAppointmentId()))
                        .filter(a -> Objects.equals(a.getDoctorId(), appointment.getDoctorId()))
                        .filter(a -> Objects.equals(a.getAppointmentTime(), request.newTime))
                        .count();

                if (currentBooked >= doctor.getSlotCapacity()) {
                    return OperationResult.fail(
                            ResultCode.SLOT_FULL,
                            "변경 요청 시간대가 만석입니다.",
                            suggestAlternativeSlots(appointment.getDoctorId(), request.newTime)
                    );
                }

                appointment.reschedule(request.newTime);
                return OperationResult.ok("예약 변경이 완료되었습니다.", appointment);
            } finally {
                for (int i = locks.size() - 1; i >= 0; i--) locks.get(i).unlock();
            }
        }

        OperationResult<Appointment> checkIn(CheckInRequest request) {
            Appointment appointment = appointments.get(request.appointmentId);
            if (appointment == null) {
                return OperationResult.fail(ResultCode.APPOINTMENT_NOT_FOUND, "접수할 예약을 찾을 수 없습니다.");
            }
            if (!Objects.equals(appointment.getPatientId(), request.patientId)) {
                return OperationResult.fail(ResultCode.APPOINTMENT_OWNER_MISMATCH, "본인 예약만 접수할 수 있습니다.");
            }
            if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
                return OperationResult.fail(ResultCode.BUSINESS_RULE_REJECTED, "접수 가능한 예약 상태가 아닙니다.");
            }
            if (!Objects.equals(appointment.getAppointmentTime().toLocalDate(), LocalDate.now())) {
                return OperationResult.fail(ResultCode.NO_TODAY_APPOINTMENT, "당일 예약이 아닙니다.");
            }

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime start = appointment.getAppointmentTime().minus(CHECK_IN_OPEN_BEFORE);
            LocalDateTime end = appointment.getAppointmentTime().plus(CHECK_IN_CLOSE_AFTER);
            if (now.isBefore(start) || now.isAfter(end)) {
                return OperationResult.fail(
                        ResultCode.INVALID_CHECKIN_WINDOW,
                        "현재는 접수 가능 시간이 아닙니다.",
                        Collections.singletonList("접수 가능 시간: " + format(start) + " ~ " + format(end))
                );
            }

            ReentrantLock patientLock = patientLocks.computeIfAbsent(request.patientId, k -> new ReentrantLock());
            patientLock.lock();
            try {
                appointment.checkIn();
                return OperationResult.ok("체크인이 완료되었습니다.", appointment);
            } finally {
                patientLock.unlock();
            }
        }

        OperationResult<List<Appointment>> queryAppointmentStatus(String patientId) {
            List<Appointment> result = new ArrayList<>();
            for (Appointment a : appointments.values()) {
                if (Objects.equals(a.getPatientId(), patientId)) {
                    result.add(a);
                }
            }
            result.sort(Comparator.comparing(Appointment::getAppointmentTime));
            if (result.isEmpty()) {
                return OperationResult.fail(ResultCode.APPOINTMENT_NOT_FOUND, "예약 내역이 없습니다.");
            }
            return OperationResult.ok("예약 내역 조회 성공", result);
        }

        OperationResult<Appointment> getAppointmentByBarcode(String barcode) {
            Appointment appointment = appointments.get(barcode);
            if (appointment == null) {
                return OperationResult.fail(ResultCode.APPOINTMENT_NOT_FOUND, "바코드에 해당하는 예약이 없습니다.");
            }
            return OperationResult.ok("예약 조회 성공", appointment);
        }

        List<Appointment> getAppointmentsByDoctorAndTime(String doctorId, LocalDateTime time) {
            List<Appointment> list = new ArrayList<>();
            for (Appointment a : appointments.values()) {
                if (a.getStatus() == AppointmentStatus.SCHEDULED
                        && Objects.equals(a.getDoctorId(), doctorId)
                        && Objects.equals(a.getAppointmentTime(), time)) {
                    list.add(a);
                }
            }
            return list;
        }

        String findFirstAppointmentIdByPatient(String patientId) {
            return appointments.values().stream()
                    .filter(a -> Objects.equals(a.getPatientId(), patientId))
                    .filter(a -> a.getStatus() == AppointmentStatus.SCHEDULED)
                    .sorted(Comparator.comparing(Appointment::getAppointmentTime))
                    .map(Appointment::getAppointmentId)
                    .findFirst()
                    .orElse(null);
        }

        String createForceAppointmentForDemo(String patientId, String doctorId, LocalDateTime time) {
            String appointmentId = "APT-DEMO-" + UUID.randomUUID().toString().substring(0, 8);
            Appointment appointment = new Appointment(appointmentId, patientId, doctorId, time);
            appointments.put(appointmentId, appointment);
            return appointmentId;
        }

        private boolean hasOutstandingBills(String patientId) {
            for (Bill bill : bills.values()) {
                if (Objects.equals(bill.getPatientId(), patientId) && !bill.isPaid()) {
                    return true;
                }
            }
            return false;
        }

        private long countActiveAppointmentsByPatient(String patientId) {
            return appointments.values().stream()
                    .filter(Appointment::isActive)
                    .filter(a -> Objects.equals(a.getPatientId(), patientId))
                    .count();
        }

        private boolean hasOverlappingAppointment(String patientId, LocalDateTime time, String ignoreAppointmentId) {
            for (Appointment a : appointments.values()) {
                if (!a.isActive()) continue;
                if (!Objects.equals(a.getPatientId(), patientId)) continue;
                if (ignoreAppointmentId != null && Objects.equals(a.getAppointmentId(), ignoreAppointmentId)) continue;
                if (Objects.equals(a.getAppointmentTime(), time)) return true;
            }
            return false;
        }

        private List<String> suggestAlternativeSlots(String doctorId, LocalDateTime baseTime) {
            List<String> suggestions = new ArrayList<>();
            Doctor doctor = doctors.get(doctorId);
            for (int delta = 1; delta <= 3; delta++) {
                LocalDateTime candidate1 = baseTime.plusHours(delta);
                LocalDateTime candidate2 = baseTime.minusHours(delta);
                if (isSlotAvailable(doctor, candidate1)) suggestions.add(format(candidate1));
                if (isSlotAvailable(doctor, candidate2)) suggestions.add(format(candidate2));
                if (suggestions.size() >= 3) break;
            }
            if (suggestions.isEmpty()) suggestions.add("가까운 다른 일정 없음");
            return suggestions;
        }

        private boolean isSlotAvailable(Doctor doctor, LocalDateTime time) {
            long count = appointments.values().stream()
                    .filter(a -> a.getStatus() == AppointmentStatus.SCHEDULED)
                    .filter(a -> Objects.equals(a.getDoctorId(), doctor.getDoctorId()))
                    .filter(a -> Objects.equals(a.getAppointmentTime(), time))
                    .count();
            return count < doctor.getSlotCapacity();
        }

        private String buildDoctorSlotKey(String doctorId, LocalDateTime time) {
            return doctorId + "@" + time.toString();
        }

        private void lockBoth(ReentrantLock a, ReentrantLock b) {
            if (System.identityHashCode(a) < System.identityHashCode(b)) {
                a.lock(); b.lock();
            } else if (a == b) {
                a.lock();
            } else {
                b.lock(); a.lock();
            }
        }

        private void unlockBoth(ReentrantLock a, ReentrantLock b) {
            if (a == b) {
                a.unlock();
                return;
            }
            a.unlock();
            b.unlock();
        }
    }

    /* =========================================================
     * Kiosk Terminal (client-side orchestrator)
     * ========================================================= */
    static class KioskTerminal {
        private static final int MAX_AUTH_RETRY = 3;

        private final String terminalId;
        private final HospitalServer server;
        private final CardReader cardReader;
        private final KeyboardDisplay keyboardDisplay;
        private final BarcodeScanner barcodeScanner;
        private final ReceiptPrinter receiptPrinter;

        private volatile TerminalState state = TerminalState.OFFLINE;
        private volatile Session currentSession;

        KioskTerminal(
                String terminalId,
                HospitalServer server,
                CardReader cardReader,
                KeyboardDisplay keyboardDisplay,
                BarcodeScanner barcodeScanner,
                ReceiptPrinter receiptPrinter
        ) {
            this.terminalId = terminalId;
            this.server = server;
            this.cardReader = cardReader;
            this.keyboardDisplay = keyboardDisplay;
            this.barcodeScanner = barcodeScanner;
            this.receiptPrinter = receiptPrinter;
        }

        synchronized void startUp(String operatorId, String password) {
            if (state != TerminalState.OFFLINE) {
                keyboardDisplay.showMessage(terminalId, "Start Up 실패: 단말이 Offline 상태가 아닙니다. 현재 상태=" + state);
                return;
            }
            OperationResult<Operator> auth = server.authenticateOperator(operatorId, password);
            if (!auth.isSuccess()) {
                keyboardDisplay.showMessage(terminalId, auth.getMessage());
                return;
            }
            state = TerminalState.IDLE;
            keyboardDisplay.showMessage(terminalId, "단말 시작 완료. 상태 => IDLE");
        }

        synchronized void shutDown(String operatorId, String password) {
            if (state != TerminalState.IDLE) {
                keyboardDisplay.showMessage(terminalId, "Shut Down 실패: 단말이 Idle 상태가 아닙니다. 현재 상태=" + state);
                return;
            }
            OperationResult<Operator> auth = server.authenticateOperator(operatorId, password);
            if (!auth.isSuccess()) {
                keyboardDisplay.showMessage(terminalId, auth.getMessage());
                return;
            }
            state = TerminalState.OFFLINE;
            keyboardDisplay.showMessage(terminalId, "단말 종료 완료. 상태 => OFFLINE");
        }

        synchronized void beginPatientSession(CardData rawCardData) {
            if (state != TerminalState.IDLE) {
                keyboardDisplay.showMessage(terminalId, "환자 세션 시작 불가. 현재 상태=" + state);
                return;
            }
            state = TerminalState.AUTHENTICATING;
            int retry = 0;
            while (retry < MAX_AUTH_RETRY) {
                CardData cardData = cardReader.readCard(rawCardData);
                OperationResult<Patient> auth = server.authenticatePatient(cardData);
                if (auth.isSuccess()) {
                    currentSession = Session.forPatient(auth.getData());
                    state = TerminalState.SELECTING_SERVICE;
                    keyboardDisplay.showMessage(terminalId, auth.getMessage() + " / 상태 => SELECTING_SERVICE");
                    keyboardDisplay.showMenu(terminalId, Arrays.asList(
                            "Make Appointment",
                            "Cancel Appointment",
                            "Reschedule Appointment",
                            "Check In",
                            "Query Appointment Status",
                            "Cancel Transaction"
                    ));
                    return;
                }
                retry++;
                keyboardDisplay.showMessage(terminalId, auth.getMessage() + " (재시도 " + retry + "/" + MAX_AUTH_RETRY + ")");
            }
            keyboardDisplay.showMessage(terminalId, "최대 인증 재시도 횟수를 초과했습니다. 세션을 종료합니다.");
            currentSession = null;
            state = TerminalState.IDLE;
        }

        synchronized void cancelTransaction() {
            if (state == TerminalState.OFFLINE || state == TerminalState.IDLE) {
                keyboardDisplay.showMessage(terminalId, "취소할 진행 중 거래가 없습니다.");
                return;
            }
            state = TerminalState.CANCELLING;
            keyboardDisplay.showMessage(terminalId, "사용자 요청으로 거래를 취소합니다.");
            currentSession = null;
            state = TerminalState.IDLE;
            keyboardDisplay.showMessage(terminalId, "상태 => IDLE");
        }

        void makeAppointment(CardData card, String doctorId, LocalDateTime desiredTime) {
            if (!preparePatientAction(card, ServiceType.MAKE_APPOINTMENT)) return;
            state = TerminalState.PROCESSING_REQUEST;
            Patient patient = currentSession.patient;
            OperationResult<Appointment> result = server.makeAppointment(
                    new MakeAppointmentRequest(patient.getPatientId(), doctorId, desiredTime)
            );
            handleAppointmentResult(ServiceType.MAKE_APPOINTMENT, result, true);
        }

        void cancelAppointment(CardData card, String appointmentId) {
            if (!preparePatientAction(card, ServiceType.CANCEL_APPOINTMENT)) return;
            state = TerminalState.PROCESSING_REQUEST;
            Patient patient = currentSession.patient;
            OperationResult<Appointment> result = server.cancelAppointment(
                    new CancelAppointmentRequest(patient.getPatientId(), appointmentId)
            );
            handleAppointmentResult(ServiceType.CANCEL_APPOINTMENT, result, false);
        }

        void rescheduleAppointment(CardData card, String appointmentId, LocalDateTime newTime) {
            if (!preparePatientAction(card, ServiceType.RESCHEDULE_APPOINTMENT)) return;
            state = TerminalState.PROCESSING_REQUEST;
            Patient patient = currentSession.patient;
            OperationResult<Appointment> result = server.rescheduleAppointment(
                    new RescheduleAppointmentRequest(patient.getPatientId(), appointmentId, newTime)
            );
            handleAppointmentResult(ServiceType.RESCHEDULE_APPOINTMENT, result, true);
        }

        void checkIn(CardData card, String appointmentId) {
            if (!preparePatientAction(card, ServiceType.CHECK_IN)) return;
            state = TerminalState.PROCESSING_REQUEST;
            Patient patient = currentSession.patient;
            OperationResult<Appointment> result = server.checkIn(
                    new CheckInRequest(patient.getPatientId(), appointmentId)
            );
            handleAppointmentResult(ServiceType.CHECK_IN, result, false);
        }

        void queryAppointmentStatus(CardData card) {
            if (!preparePatientAction(card, ServiceType.QUERY_APPOINTMENT_STATUS)) return;
            state = TerminalState.PROCESSING_REQUEST;
            Patient patient = currentSession.patient;
            OperationResult<List<Appointment>> result = server.queryAppointmentStatus(patient.getPatientId());
            if (result.isSuccess()) {
                state = TerminalState.COMPLETING;
                keyboardDisplay.showMessage(terminalId, result.getMessage());
                StringBuilder sb = new StringBuilder();
                sb.append("[예약 현황]\n");
                for (Appointment a : result.getData()) {
                    sb.append("- ").append(a).append("\n");
                }
                receiptPrinter.print(terminalId, sb.toString());
            } else {
                keyboardDisplay.showMessage(terminalId, result.getMessage());
            }
            finishSession();
        }

        void scanReceiptAndShow(String barcode) {
            if (state == TerminalState.OFFLINE) {
                keyboardDisplay.showMessage(terminalId, "단말이 Offline 상태입니다.");
                return;
            }
            String scanned = barcodeScanner.scanBarcode(barcode);
            OperationResult<Appointment> result = server.getAppointmentByBarcode(scanned);
            if (result.isSuccess()) {
                keyboardDisplay.showMessage(terminalId, "바코드 예약 조회 성공: " + result.getData());
            } else {
                keyboardDisplay.showMessage(terminalId, result.getMessage());
            }
        }

        private boolean preparePatientAction(CardData card, ServiceType serviceType) {
            if (state == TerminalState.OFFLINE) {
                keyboardDisplay.showMessage(terminalId, "단말이 Offline 상태입니다.");
                return false;
            }
            beginPatientSession(card);
            if (state != TerminalState.SELECTING_SERVICE || currentSession == null) {
                return false;
            }
            currentSession.selectedService = serviceType;
            keyboardDisplay.showMessage(terminalId, "선택 서비스 => " + serviceType);
            return true;
        }

        private void handleAppointmentResult(ServiceType serviceType, OperationResult<Appointment> result, boolean printReceipt) {
            if (result.isSuccess()) {
                state = TerminalState.COMPLETING;
                keyboardDisplay.showMessage(terminalId, result.getMessage());
                if (printReceipt) {
                    receiptPrinter.print(terminalId, buildReceipt(serviceType, result.getData()));
                }
            } else {
                keyboardDisplay.showMessage(terminalId, "실패: " + result.getMessage());
                if (!result.getSuggestions().isEmpty()) {
                    keyboardDisplay.showMessage(terminalId, "안내: " + result.getSuggestions());
                }
                if (result.getCode() == ResultCode.OUTSTANDING_BILLS) {
                    receiptPrinter.print(terminalId, "[수납 안내]\n미납금이 존재하여 예약이 제한됩니다.\n수납 창구를 이용해 주세요.");
                }
            }
            finishSession();
        }

        private String buildReceipt(ServiceType serviceType, Appointment appointment) {
            String title;
            switch (serviceType) {
                case MAKE_APPOINTMENT: title = "예약 확인증"; break;
                case RESCHEDULE_APPOINTMENT: title = "예약 변경 확인증"; break;
                case CHECK_IN: title = "접수 확인증"; break;
                default: title = "처리 확인증";
            }
            return "[" + title + "]\n" +
                    "단말: " + terminalId + "\n" +
                    "예약번호(바코드): " + appointment.getAppointmentId() + "\n" +
                    "환자ID: " + appointment.getPatientId() + "\n" +
                    "의사ID: " + appointment.getDoctorId() + "\n" +
                    "예약시간: " + format(appointment.getAppointmentTime()) + "\n" +
                    "상태: " + appointment.getStatus() + "\n" +
                    "출력시각: " + format(LocalDateTime.now());
        }

        private void finishSession() {
            currentSession = null;
            state = TerminalState.IDLE;
            keyboardDisplay.showMessage(terminalId, "세션 종료 / 상태 => IDLE");
        }
    }

    /* =========================================================
     * Session
     * ========================================================= */
    static class Session {
        private final Patient patient;
        private ServiceType selectedService;

        private Session(Patient patient) {
            this.patient = patient;
        }

        static Session forPatient(Patient patient) {
            return new Session(patient);
        }
    }

    /* =========================================================
     * Sample Data
     * ========================================================= */
    static class SampleDataFactory {
        static HospitalServer createServerWithSampleData() {
            HospitalServer server = new HospitalServer();

            server.addOperator(new Operator("op-001", "Main Operator", "admin123"));

            server.addPatient(new Patient("P1001", "Kim Minsoo", "900101-1111111", "H-1001", 3));
            server.addPatient(new Patient("P1002", "Lee Sora", "920202-2222222", "H-1002", 3));
            server.addPatient(new Patient("P1003", "Park Jiyun", "930303-3333333", "H-1003", 3));

            server.addDoctor(new Doctor("D001", "Dr. Han", "Internal Medicine", 1));
            server.addDoctor(new Doctor("D002", "Dr. Seo", "Orthopedics", 2));
            server.addDoctor(new Doctor("D003", "Dr. Choi", "Dermatology", 1));

            server.addBill(new Bill("B001", "P1002", 50000, false));
            server.addBill(new Bill("B002", "P1001", 12000, true));

            server.addMedicalRecord(new MedicalRecord(
                    "MR001",
                    "P1001",
                    Arrays.asList("2025-11-21 감기 진료", "2026-01-03 정기 검진")
            ));

            return server;
        }
    }

    /* =========================================================
     * Utility
     * ========================================================= */
    static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static String format(LocalDateTime dt) {
        return dt.format(DTF);
    }
}
