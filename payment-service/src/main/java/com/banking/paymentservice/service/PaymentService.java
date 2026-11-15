package com.banking.paymentservice.service;

import com.banking.paymentservice.dto.CreatePaymentRequest;
import com.banking.paymentservice.dto.PaymentOrderResponse;
import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.exception.InvalidAmountException;
import com.banking.paymentservice.exception.InvalidWebhookException;
import com.banking.paymentservice.exception.PaymentNotFoundException;
import com.banking.paymentservice.exception.PaymentOrderCreationException;
import com.banking.paymentservice.repository.PaymentRepository;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${razorpay.key-id}")
    private String keyId;

    @Value("${razorpay.key-secret}")
    private String keySecret;

    @Value("${razorpay.webhook-secret}")
    private String webhookSecret;

    private static final String PAYMENT_COMPLETED_TOPIC = "payment.completed";
    private static final String PAYMENT_FAILED_TOPIC = "payment.failed";

    /**
     * Create Razorpay payment order
     * <p>
     * FLOW:
     *  1. Create order in Razorpay
     *  2. Save payment record in DB
     *  3. Return order details to FrontEnd
     *  4. Frontend show Razorpay checkout
     *  5. User pays
     *  6. Razorpay call webhook
     * @param request -> CreatePaymentRequest
     * @return -> PaymentOrderResponse
     */
    public PaymentOrderResponse createPaymentOrder(CreatePaymentRequest request) throws RazorpayException {

        long amountInPaise = toPaise(request.getAmount());

        // 1. Create order in Razorpay
        log.info("Creating payment order for account: {} amount: {}",
                request.getAccountNumber(), request.getAmount());

        Order razorpayOrder;


        try{

            RazorpayClient razorpayClient = new RazorpayClient(keyId, keySecret);

            // Converted Amount
            int convertedAmount = request.getAmount()
                    .multiply(BigDecimal.valueOf(100))
                    .intValue();

            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount",convertedAmount);
            orderRequest.put("currency","USD");
            orderRequest.put("receipt","rcpt_" +
                    System.currentTimeMillis() +
                    UUID.randomUUID().toString()
                            .replace("-","").substring(0,10));

            razorpayOrder = razorpayClient.orders.create(orderRequest);

            log.info("Razorpay order created: {}",razorpayOrder.get("id").toString());
        }catch (RazorpayException e) {
            throw new PaymentOrderCreationException(e);
        }


        // 2. Save payment record
        Payment payment = new Payment();
        payment.setRazorpayOrderId(razorpayOrder.get("id").toString());
        payment.setAccountNumber(request.getAccountNumber());
        payment.setAmount(request.getAmount());
        payment.setCurrency("USD");
        payment.setStatus(PaymentStatus.CREATED);
        payment.setDescription(request.getDescription());

        Payment savedPayment = paymentRepository.save(payment);


        return new PaymentOrderResponse(
                savedPayment.getId(),
                razorpayOrder.get("id").toString(),
                request.getAmount(),
                "USD",
                "CREATED",
                keyId
        );

    }

    private long toPaise(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidAmountException("Amount must be greater than zero");
        }
        try {
            // rejects more than 2 decimal places instead of silently truncating
            return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new InvalidAmountException("Amount can have at most 2 decimal places");
        }
    }


    public void handleWebhook(String rawBody, String signature) {

        verifySignature(rawBody, signature);

        JsonNode root = objectMapper.readTree(rawBody);


        String event = root.path("event").stringValue();

        log.info("Received Razorpay webhook: {}",event);

        if("payment.captured".equals(event)){
            handlePaymentSuccess(root);
        }
        else if ("payment.failed".equals(event)) {
            handlePaymentFailure(root);
        }else {
            log.debug("Ignoring webhook event: {}", event);
        }
    }

    private void verifySignature(String rawBody, String signature) {
        try {
            if (signature == null || !Utils.verifyWebhookSignature(rawBody, signature, webhookSecret)) {
                throw new InvalidWebhookException("Invalid webhook signature");
            }
        } catch (RazorpayException e) {
            throw new InvalidWebhookException("Webhook signature verification failed");
        }
    }


    private void handlePaymentSuccess(JsonNode root) {
        try {

            JsonNode paymentData = extractPaymentData(root);
            String orderId = requireText(paymentData, "order_id");
            String paymentId = requireText(paymentData, "id");

            Payment payment = paymentRepository.findByRazorpayOrderId(orderId)
                    .orElseThrow(() -> new PaymentNotFoundException(orderId));


            // Razorpay may deliver the same webhook more than once
            if (payment.getStatus() == PaymentStatus.COMPLETED) {
                log.info("Payment {} already completed - ignoring duplicate webhook", payment.getId());
                return;
            }

            payment.setRazorpayPaymentId(paymentId);
            payment.setStatus(PaymentStatus.COMPLETED);
            paymentRepository.save(payment);

            // Publish payment completed event
            Map<String, Object> event = new HashMap<>();
            event.put("paymentId",payment.getId());
            event.put("accountNumber",payment.getAccountNumber());
            event.put("amount",payment.getAmount());
            event.put("razorpayPaymentId",paymentId);

            kafkaTemplate.send(PAYMENT_COMPLETED_TOPIC, payment.getId(), event);
            log.info("Payment completed: {}",payment.getId());


        } catch (Exception e) {
            log.error("Error handling payment success: {}", e.getMessage());
        }
    }

    private void handlePaymentFailure(JsonNode root) {
        try {

            String orderId = requireText(extractPaymentData(root), "order_id");


            Payment payment = paymentRepository.findByRazorpayOrderId(orderId)
                    .orElseThrow(() -> new PaymentNotFoundException(orderId));

            // A failed attempt must not overwrite a payment that later succeeded
            if (payment.getStatus() == PaymentStatus.COMPLETED) {
                log.info("Payment {} already completed - ignoring failure event", payment.getId());
                return;
            }

            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Payment failed via Razorpay");
            paymentRepository.save(payment);


            // Publish payment failed event
            Map<String, Object> event = new HashMap<>();
            event.put("paymentId",payment.getId());
            event.put("accountNumber",payment.getAccountNumber());
            event.put("amount",payment.getAmount());
            event.put("reason","Payment failed via Razorpay");

            kafkaTemplate.send(PAYMENT_FAILED_TOPIC, payment.getId(), event);
            log.warn("Payment failed: {}", payment.getId());

        } catch (Exception e) {
            log.error("Error handling payment failure: {}", e.getMessage());
        }
    }


    private JsonNode extractPaymentData(JsonNode root) {

        return root.path("payload").path("payment").path("entity");
    }

    private String requireText(JsonNode node, String field) {
        String value = node.path(field).stringValue();
        if (value == null || value.isBlank()) {
            throw new InvalidWebhookException("Webhook payload is missing: " + field);
        }
        return value;
    }
}
