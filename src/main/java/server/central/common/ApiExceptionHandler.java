package server.central.common;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.stream.Collectors;
import server.central.node.NodePeerClient;

@RestControllerAdvice
/** API 예외를 일관된 RFC 9457 ProblemDetail 응답으로 변환한다. */
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> badRequest(
            IllegalArgumentException error, HttpServletRequest request)
    {
        return problem(HttpStatus.BAD_REQUEST, error, request);
    }

    /** Bean Validation의 클래스명과 내부 코드를 노출하지 않고 사용자가 수정할 수 있는 필드별 안내만 하나의 문장으로 조합한다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validationFailed(
            MethodArgumentNotValidException error, HttpServletRequest request)
    {
        String message =
                error.getBindingResult().getFieldErrors().stream()
                        .map(
                                fieldError ->
                                        fieldError.getDefaultMessage() == null
                                                ? fieldError.getField() + " 값을 확인하세요."
                                                : fieldError.getDefaultMessage())
                        .distinct()
                        .collect(Collectors.joining(" "));
        return problem(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(NodePeerClient.RemoteRequestException.class)
    ResponseEntity<ProblemDetail> remoteRequest(
            NodePeerClient.RemoteRequestException error, HttpServletRequest request)
    {
        HttpStatus status = HttpStatus.resolve(error.statusCode());
        return problem(status == null ? HttpStatus.BAD_GATEWAY : status, error, request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> conflict(Exception error, HttpServletRequest request)
    {
        return problem(HttpStatus.CONFLICT, error, request);
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status, Exception error, HttpServletRequest request)
    {
        String message = error.getMessage() == null ? status.getReasonPhrase() : error.getMessage();
        return problem(status, message, request);
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String message, HttpServletRequest request)
    {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(status.getReasonPhrase());
        detail.setInstance(URI.create(request.getRequestURI()));
        detail.setProperty("code", status.name());
        return ResponseEntity.status(status).body(detail);
    }
}
