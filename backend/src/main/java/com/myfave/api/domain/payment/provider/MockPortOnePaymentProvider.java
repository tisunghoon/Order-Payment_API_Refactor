package com.myfave.api.domain.payment.provider;

import com.myfave.api.domain.payment.entity.Payment;
import com.myfave.api.domain.payment.repository.PaymentRepository;
import com.myfave.api.global.error.CustomException;
import com.myfave.api.global.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.function.Supplier;

// 부하 테스트 전용 PaymentProvider mock.
// 외부 PortOne API 호출 없이 항상 PAID 상태로 응답해 시나리오 D의 PESSIMISTIC 락 검증을 가능하게 함.
// pgTransactionId 패턴 "MOCK-PAY-{paymentId}"에서 paymentId 파싱 → DB에서 실제 결제 금액 조회.
@Slf4j
@Component
@Profile("loadtest")
@Primary
@RequiredArgsConstructor
public class MockPortOnePaymentProvider implements PaymentProvider {

    private static final String MOCK_PREFIX = "MOCK-PAY-";

    private final PaymentRepository paymentRepository;
    private final MeterRegistry meterRegistry;

    @Override
    public PortOnePaymentInfo getPaymentInfo(String pgTransactionId) {
        return invokeWithMetrics("getPaymentInfo", () -> {
            int amount = resolveAmount(pgTransactionId);
            return new PortOnePaymentInfo(
                    pgTransactionId,
                    "PAID",
                    amount,
                    "https://mock.receipt/" + pgTransactionId,
                    ZonedDateTime.now()
            );
        });
    }

    @Override
    public void cancelPayment(String pgTransactionId, int cancelAmount, String reason) {
        invokeWithMetrics("cancelPayment", () -> {
            log.debug("[Mock] cancel pgTransactionId={}, amount={}, reason={}",
                    pgTransactionId, cancelAmount, reason);
            return null;
        });
    }

    // 실제 PortOnePaymentProvider와 동일하게 PG 호출을 Timer.Sample로 감싸 outcome별 latency를 기록한다.
    // Mock은 외부 HTTP 호출이 없지만, loadtest 프로파일에서도 myfave.portone.call.duration 패널이
    // 채워지도록 동일한 메트릭(api/outcome 태그)을 남긴다.
    private <T> T invokeWithMetrics(String api, Supplier<T> call) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "failure";
        try {
            T result = call.get();
            outcome = "success";
            return result;
        } finally {
            sample.stop(Timer.builder("myfave.portone.call.duration")
                    .tag("api", api)
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }

    private int resolveAmount(String pgTransactionId) {
        if (pgTransactionId == null || !pgTransactionId.startsWith(MOCK_PREFIX)) {
            return 13000;
        }
        try {
            Long paymentId = Long.parseLong(pgTransactionId.substring(MOCK_PREFIX.length()));
            Payment payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_NOT_FOUND));
            return payment.getTotalPaymentPrice();
        } catch (NumberFormatException e) {
            return 13000;
        }
    }
}
