package com.banking.notificationservice.service;

import com.banking.notificationservice.client.AccountServiceClient;
import com.banking.notificationservice.exception.AccountServiceUnavailableException;
import com.banking.notificationservice.exception.CustomerEmailNotFoundException;
import com.banking.notificationservice.exception.EmailDeliveryException;
import com.banking.notificationservice.exception.InvalidNotificationPayloadException;
import feign.FeignException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {

    private final JavaMailSender mailSender;

    private final AccountServiceClient accountServiceClient;


    private static final String COLOR_SUCCESS = "#2e7d32";
    private static final String COLOR_DANGER  = "#c62828";
    private static final String COLOR_WARNING = "#ef6c00";

    @Value("${spring.mail.username}")
    private String senderEmail;

    @KafkaListener(topics = "transaction.otp.generated")
    public void consumeOtpGenerated(
            @Payload Map<String, Object> payload){

        String transactionId = requireField(payload, "transactionId");

        String message = buildEmailTemplate(
                requireField(payload, "accountNumber"),
                transactionId,
                requireField(payload, "amount"),
                requireField(payload, "reason"),
                requireField(payload, "otp"));

        sendAlert(requireField(payload, "email"), "Transaction Verification Required", message);
        log.info("OTP notification sent for transaction: {}", transactionId);
    }

    @KafkaListener(topics = "transaction.completed", groupId = "notification-debit")
    public void consumeDebitAlert(@Payload Map<String, Object> payload) {
        String sender = requireField(payload, "senderAccountNumber");

        Map<String, String> details = new LinkedHashMap<>();
        details.put("Account", mask(sender));
        details.put("Transaction ID", val(payload, "transactionId"));
        details.put("Amount debited", requireField(payload, "amount"));
        details.put("Paid to", mask(requireField(payload, "receiverAccountNumber")));
        details.put("Description", val(payload, "description"));
        details.put("Status", "Completed");

        sendAlert(emailOf(sender), "Debit Alert",
                buildEmail("Transaction Alert", "Amount Debited", COLOR_DANGER,
                        "An amount has been debited from your account.", details,
                        "If you did not make this transaction, please contact your bank immediately."));
    }

    @KafkaListener(topics = "transaction.completed", groupId = "notification-credit")
    public void consumeCreditAlert(@Payload Map<String, Object> payload) {
        String receiver = requireField(payload, "receiverAccountNumber");

        Map<String, String> details = new LinkedHashMap<>();
        details.put("Account", mask(receiver));
        details.put("Transaction ID", val(payload, "transactionId"));
        details.put("Amount credited", requireField(payload, "amount"));
        details.put("Received from", mask(requireField(payload, "senderAccountNumber")));
        details.put("Description", val(payload, "description"));
        details.put("Status", "Completed");

        sendAlert(emailOf(receiver), "Credit Alert",
                buildEmail("Transaction Alert", "Amount Credited", COLOR_SUCCESS,
                        "An amount has been credited to your account.", details, null));
    }


    @KafkaListener(topics = "fraud.detected")
    public void consumeFraudDetected(
            @Payload Map<String, Object> payload){

        String accountNumber = requireField(payload, "accountNumber");


        Map<String, String> details = new LinkedHashMap<>();
        details.put("Account", mask(accountNumber));
        details.put("Transaction ID", val(payload, "transactionId"));
        details.put("Reason", val(payload, "reason"));
        details.put("Account status", "Blocked");

        sendAlert(emailOf(accountNumber),
                "Suspicious Activity Detected",
                buildEmail("Security Alert",
                        "Account Blocked", COLOR_DANGER,
                        "We detected suspicious activity and have blocked your account to keep your money safe.",
                        details,
                        "Please contact your bank through its official support channel to restore access."));

    }


    @KafkaListener(topics = "transaction.refunded")
    public void consumeTransactionRefunded(
            @Payload Map<String, Object> payload){

        String senderAccount = requireField(payload, "senderAccountNumber");


        Map<String, String> details = new LinkedHashMap<>();
        details.put("Account", mask(senderAccount));
        details.put("Transaction ID", val(payload, "transactionId"));
        details.put("Amount refunded", requireField(payload, "amount"));
        details.put("Reason", val(payload, "reason"));
        details.put("Status", "Refunded");

        sendAlert(emailOf(senderAccount),
                "Refund Processed",
                buildEmail("Transaction Alert",
                        "Transaction Failed - Amount Refunded", COLOR_WARNING,
                        "Your transfer could not be completed, and the full amount has been returned to your account.",
                        details,
                        null));

    }



    @KafkaListener(topics = "payment.completed")
    public void consumePaymentCompleted(
            @Payload Map<String, Object> payload){


        String accountNumber = requireField(payload, "accountNumber");

        Map<String, String> details = new LinkedHashMap<>();
        details.put("Account", mask(accountNumber));
        details.put("Amount", requireField(payload, "amount"));
        details.put("Payment ID", requireField(payload, "razorpayPaymentId"));
        details.put("Status", "Successful");

        sendAlert(emailOf(accountNumber),
                "Payment Successful",
                buildEmail("Payment Alert",
                        "Payment Successful", COLOR_SUCCESS,
                        "Your payment has been processed successfully.",
                        details,
                        null));

    }


    @KafkaListener(topics = "payment.failed")
    public void consumePaymentFailed(
            @Payload Map<String, Object> payload) {


        String accountNumber = requireField(payload, "accountNumber");

        Map<String, String> details = new LinkedHashMap<>();
        details.put("Account", mask(accountNumber));
        details.put("Amount", requireField(payload, "amount"));
        details.put("Status", "Failed");
        details.put("Reference", val(payload, "paymentId"));

        sendAlert(emailOf(accountNumber),
                "Payment Failed",
                buildEmail("Payment Alert",
                        "Payment Failed", COLOR_DANGER,
                        "We could not process your payment. No money has been taken from your account.",
                        details,
                        "Please try again, or contact support if the problem continues."));
    }


    @KafkaListener(
            topicPattern = ".*\\.DLT",
            groupId = "notification-dlt",
            properties = "value.deserializer=org.apache.kafka.common.serialization.StringDeserializer")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        Header h = record.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        String error = h == null ? "n/a" : new String(h.value(), StandardCharsets.UTF_8);
        log.error("DEAD LETTER from {}: key={} error={}", record.topic(), record.key(), error);
    }



    private void sendAlert(
            String customerEmail,
            String subject,
            String message) {

        MimeMessage mimeMessage = mailSender.createMimeMessage();
        try {

            MimeMessageHelper helper =
                    new MimeMessageHelper(
                            mimeMessage, false, "UTF-8"
                    );
            helper.setFrom(senderEmail);
            helper.setTo(customerEmail);
            helper.setSubject(subject);
            helper.setText(message, true);

        } catch (MessagingException e) {
            throw new InvalidNotificationPayloadException("Invalid recipient or message: " + e.getMessage());
        }

        try {
            mailSender.send(mimeMessage);
            log.info("Email notification sent successfully");
        } catch (MailException e) {
            throw new EmailDeliveryException(e);
        }
    }


    private String buildEmailTemplate(
            String accountNumber,
            String transactionId,
            String amount,
            String reason,
            String otp) {

        String maskedAccount = mask(accountNumber);

        return """
            <!DOCTYPE html>
            <html>
            <body style="font-family:Arial,sans-serif;
                         background-color:#f4f6f8;
                         padding:20px;">

              <div style="max-width:600px;
                          margin:auto;
                          background:white;
                          border-radius:8px;
                          overflow:hidden;">

                <div style="background:#12345A;
                            color:white;
                            padding:25px;
                            text-align:center;">
                  <h2>SecureBank</h2>
                  <p>Transaction Security Alert</p>
                </div>

                <div style="padding:25px;">
                  <h3 style="color:#c62828;">
                    Transaction Verification Required
                  </h3>

                  <p>Dear Customer,</p>

                  <p>
                    We detected unusual activity
                    associated with your account.
                  </p>

                  <div style="background:#f4f6f8;
                              padding:15px;
                              border-radius:6px;">

                    <p><b>Account:</b> %s</p>
                    <p><b>Transaction ID:</b> %s</p>
                    <p><b>Amount:</b> %s</p>
                    <p><b>Reason:</b> %s</p>
                    <p><b>Status:</b>
                       Pending Verification</p>
                  </div>

                  <div style="text-align:center;
                              padding:20px;">
                    <p>Your One-Time Password</p>

                    <h1 style="color:#12345A;
                               letter-spacing:6px;">
                      %s
                    </h1>

                    <p>Valid for 5 minutes</p>
                  </div>

                  <p style="color:#c62828;">
                    Never share this OTP with anyone.
                    If you did not initiate this
                    transaction, do not use the OTP.
                    Contact your bank through its
                    official support channel.
                  </p>

                  <hr>

                  <p style="font-size:12px;
                            color:#777;">
                    This is an automated notification.
                    Please do not reply.
                  </p>
                </div>
              </div>
            </body>
            </html>
            """.formatted(
                escapeHtml(maskedAccount),
                escapeHtml(transactionId),
                escapeHtml(amount),
                escapeHtml(reason),
                escapeHtml(otp)
        );
    }

    private String escapeHtml(String value) {
        return HtmlUtils.htmlEscape(value);
    }


    private String emailOf(String accountNumber) {
        try {
            Object email = accountServiceClient.getCustomerEmailAndBalance(accountNumber).get("email");
            if (email == null || email.toString().isBlank()) {
                throw new CustomerEmailNotFoundException(accountNumber);
            }
            return email.toString();
        } catch (FeignException.NotFound e) {
            throw new CustomerEmailNotFoundException(accountNumber);
        } catch (FeignException e) {
            throw new AccountServiceUnavailableException(e);   // 5xx, timeout, connection refused
        }
    }


    private String mask(String accountNumber) {
        return "XXXX" + accountNumber.substring(Math.max(0, accountNumber.length() - 4));
    }

    /**
     * Null-safe read from the Kafka payload.
     */
    private String val(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        return v == null ? "-" : v.toString();
    }

    /**
     * For fields the email can't be built without.
     */
    private String requireField(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new InvalidNotificationPayloadException("Event is missing required field: " + key);
        }
        return v.toString();
    }


    /**
     * Shared SecureBank email layout.
     *
     * @param bannerText  small text under the bank name in the header
     * @param heading     colored title inside the card
     * @param headingColor one of the COLOR_* constants
     * @param intro       one or two sentences for the customer
     * @param details     label -> value rows (insertion order is kept; values are escaped here)
     * @param note        closing paragraph (null to omit)
     */
    private String buildEmail(String bannerText,
                              String heading,
                              String headingColor,
                              String intro,
                              Map<String, String> details,
                              String note) {

        StringBuilder rows = new StringBuilder();
        details.forEach((label, value) -> rows
                .append("<p><b>").append(escapeHtml(label)).append(":</b> ")
                .append(escapeHtml(value)).append("</p>\n"));

        String noteHtml = note == null ? "" :
                "<p style=\"color:" + headingColor + ";\">" + escapeHtml(note) + "</p>";

        return """
        <!DOCTYPE html>
        <html>
        <body style="font-family:Arial,sans-serif;background-color:#f4f6f8;padding:20px;">
          <div style="max-width:600px;margin:auto;background:white;border-radius:8px;overflow:hidden;">

            <div style="background:#12345A;color:white;padding:25px;text-align:center;">
              <h2>SecureBank</h2>
              <p>%s</p>
            </div>

            <div style="padding:25px;">
              <h3 style="color:%s;">%s</h3>

              <p>Dear Customer,</p>
              <p>%s</p>

              <div style="background:#f4f6f8;padding:15px;border-radius:6px;">
                %s
              </div>

              %s

              <hr>
              <p style="font-size:12px;color:#777;">
                This is an automated notification. Please do not reply.
              </p>
            </div>
          </div>
        </body>
        </html>
        """.formatted(
                escapeHtml(bannerText),
                headingColor,
                escapeHtml(heading),
                escapeHtml(intro),
                rows,
                noteHtml);
    }
}
