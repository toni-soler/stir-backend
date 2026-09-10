package org.stir.listing;

import java.util.Map;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ListingErrors {
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<?> conflict() { return ResponseEntity.status(409).body(Map.of("message","Listing changed; reload before editing")); }
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("message","Invalid listing filter")); }
}
