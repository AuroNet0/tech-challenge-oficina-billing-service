package com.oficina.billing.infrastructure.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import com.oficina.billing.application.payment.IntegrationUnavailableException;
import com.oficina.billing.application.payment.InvalidSignatureException;

@Component
public class MercadoPagoSignatureVerifier {
    private final MercadoPagoProperties properties;
    private final Clock clock;

    public MercadoPagoSignatureVerifier(MercadoPagoProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String verify(String resourceId, String requestId, String signature) {
        if (!properties.isEnabled()) throw new IntegrationUnavailableException();
        try {
            if (resourceId == null || !resourceId.matches("[0-9]{1,30}") || requestId == null
                    || !requestId.matches("[A-Za-z0-9-]{1,120}") || signature == null || signature.length() > 200) {
                throw new InvalidSignatureException();
            }
            String timestamp = null;
            String hash = null;
            String[] fields = signature.split(",", -1);
            if (fields.length != 2) throw new InvalidSignatureException();
            for (String field : fields) {
                String[] pair = field.trim().split("=", -1);
                if (pair.length != 2) throw new InvalidSignatureException();
                if (pair[0].equals("ts") && timestamp == null) timestamp = pair[1];
                else if (pair[0].equals("v1") && hash == null) hash = pair[1];
                else throw new InvalidSignatureException();
            }
            if (timestamp == null || !timestamp.matches("(?:[0-9]{10}|[0-9]{13})")
                    || hash == null || !hash.matches("[0-9a-fA-F]{64}")) throw new InvalidSignatureException();
            Instant signedAt = timestamp.length() == 13 ? Instant.ofEpochMilli(Long.parseLong(timestamp))
                    : Instant.ofEpochSecond(Long.parseLong(timestamp));
            if (!properties.getSignatureTolerance().isZero() && Duration.between(signedAt, clock.instant()).abs()
                    .compareTo(properties.getSignatureTolerance()) > 0) throw new InvalidSignatureException();
            String manifest = "id:" + resourceId + ";request-id:" + requestId + ";ts:" + timestamp + ";";
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (!MessageDigest.isEqual(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)), HexFormat.of().parseHex(hash))) {
                throw new InvalidSignatureException();
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (InvalidSignatureException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidSignatureException();
        }
    }
}
