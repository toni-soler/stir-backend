package org.stir.economic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClientResponseException;

/** osTRIS's own {code,message} error body, surfaced as-is - STIR never reinterprets or hides a real protocol rejection. */
public class StirOstrisException extends RuntimeException {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public final int status;
    public final String code;

    public StirOstrisException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }

    static StirOstrisException from(RestClientResponseException ex) {
        try {
            var body = MAPPER.readValue(ex.getResponseBodyAsByteArray(), java.util.Map.class);
            return new StirOstrisException(ex.getStatusCode().value(), String.valueOf(body.get("code")), String.valueOf(body.get("message")));
        } catch (Exception parseFailure) {
            return new StirOstrisException(ex.getStatusCode().value(), "OSTRIS_ERROR", ex.getMessage());
        }
    }
}
