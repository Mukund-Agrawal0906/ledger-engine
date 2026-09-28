package com.mukundagrawal.ledger.error;

import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every failure to an RFC 9457 {@code ProblemDetail} with a stable {@code code} property
 * (catalog: docs/API_CONTRACTS.md §3, mapping: docs/LLD.md §6).
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring MVC's own errors (unreadable JSON, type
 * mismatches, 404 routes, 405, 415) keep their correct statuses instead of falling into the 500 catch-all.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	private static final String LOCK_NOT_AVAILABLE = "55P03";

	@ExceptionHandler(LedgerException.class)
	ResponseEntity<ProblemDetail> handleLedger(LedgerException e) {
		return problem(e.status(), e.code(), e.getMessage());
	}

	@ExceptionHandler(DataAccessException.class)
	ResponseEntity<ProblemDetail> handleDataAccess(DataAccessException e) {
		if (LOCK_NOT_AVAILABLE.equals(sqlState(e))) {
			ResponseEntity<ProblemDetail> busy = problem(HttpStatus.SERVICE_UNAVAILABLE, "service_busy",
					"Timed out waiting for a contended account. Retry with the same Idempotency-Key.");
			return ResponseEntity.status(busy.getStatusCode()).header(HttpHeaders.RETRY_AFTER, "1").body(busy.getBody());
		}
		return internalError(e);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
		return internalError(e);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<Map<String, String>> errors = e.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
			.toList();
		ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed.").getBody();
		body.setProperty("errors", errors);
		return ResponseEntity.badRequest().body(body);
	}

	/**
	 * Tags Spring MVC's built-in 400s (unreadable/unknown-field JSON, bad UUIDs, method-validation failures
	 * on query params, ...) as validation_failed. Done on the super result: for some exceptions (e.g.
	 * HandlerMethodValidationException) {@code body} arrives null and the ProblemDetail is only built there.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		ResponseEntity<Object> response = super.handleExceptionInternal(e, body, headers, status, request);
		if (response != null && response.getStatusCode().value() == 400 && response.getBody() instanceof ProblemDetail pd) {
			pd.setType(type("validation_failed"));
			pd.setProperty("code", "validation_failed");
		}
		return response;
	}

	private ResponseEntity<ProblemDetail> internalError(Exception e) {
		// Includes 40P01 deadlocks and invariant backstops firing at commit: these are bugs, not client errors.
		log.error("Unhandled error", e);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
				"Unexpected error; nothing was committed. Safe to retry with the same Idempotency-Key.");
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String code, String detail) {
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
		pd.setType(type(code));
		pd.setProperty("code", code);
		return ResponseEntity.status(status).body(pd);
	}

	private static URI type(String code) {
		return URI.create("https://ledger-engine.dev/problems/" + code.replace('_', '-'));
	}

	private static String sqlState(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c instanceof SQLException sql && sql.getSQLState() != null) {
				return sql.getSQLState();
			}
		}
		return null;
	}

}
