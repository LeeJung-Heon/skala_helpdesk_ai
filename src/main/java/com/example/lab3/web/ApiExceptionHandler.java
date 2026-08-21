package com.example.lab3.web;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 실패를 <b>읽을 수 있는 응답</b>으로 바꾼다.
 *
 * <p>이것이 없으면 모든 실패가 스프링 기본 500 으로 나가고, 화면에는
 * {@code [500] 응답 없음} 만 남는다. 모델 인증 실패인지, 호출 한도인지,
 * 우리 코드의 버그인지 구분할 수 없어 디버깅이 <b>로그 뒤지기부터</b> 시작된다.
 *
 * <p>실제로 그 상황을 겪었다. 폐기된 API 키로 호출했더니 화면에는 아무 단서도
 * 남지 않았고, 서버 로그를 열어서야 401 인 것을 알았다. 이 클래스는 그 왕복을 없앤다.
 *
 * <p>원인 문자열을 응답에 그대로 싣지는 않는다 — 스택트레이스와 내부 경로가
 * 클라이언트로 새어 나가면 그 자체가 정찰 정보가 된다. 대신 {@code traceId} 를 주고
 * 자세한 내용은 서버 로그에서 찾게 한다.
 *
 * <h2>{@link ResponseEntityExceptionHandler} 를 상속하는 이유</h2>
 * {@code @ExceptionHandler(Exception.class)} 만 두면 <b>프레임워크가 이미 제대로 처리하던
 * 예외까지 삼킨다.</b> 실제로 그랬다 — 브라우저가 없는 {@code /favicon.ico} 를 찾자
 * 404 로 끝났어야 할 요청이 500 이 되고 스택트레이스가 로그에 쌓였다.
 * 필수 파라미터 누락(400)도 같은 식으로 500 이 됐다.
 *
 * <p>부모 클래스가 스프링 MVC 표준 예외들을 더 <b>구체적인</b> 핸들러로 잡아 주므로,
 * 아래 catch-all 은 정말 예상 못 한 것에만 걸린다.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 도구가 던지는 "주문번호가 비었습니다" 류 — 사용자 입력 문제다. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        String traceId = newTraceId();
        log.warn("[{}] 잘못된 요청: {}", traceId, e.getMessage());
        return body(HttpStatus.BAD_REQUEST, "bad_request",
                "요청이 올바르지 않습니다.", traceId);
    }

    /**
     * 나머지 전부. 모델 호출 실패는 따로 골라 <b>무엇을 고쳐야 하는지</b> 알려 준다.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        String traceId = newTraceId();
        Throwable root = rootCause(e);

        if (isUnauthorized(root)) {
            // 가장 흔한 실수 — 키를 안 넣었거나 폐기된 키를 쓰는 경우
            log.error("[{}] 모델 인증 실패 — OPENAI_API_KEY 를 확인하라", traceId);
            return body(HttpStatus.SERVICE_UNAVAILABLE, "ai_unauthorized",
                    "모델 인증에 실패했습니다. OPENAI_API_KEY 를 확인하세요.", traceId);
        }
        if (isRateLimited(root)) {
            log.error("[{}] 모델 호출 한도 초과", traceId);
            return body(HttpStatus.SERVICE_UNAVAILABLE, "ai_rate_limited",
                    "모델 호출 한도를 초과했습니다. 잠시 후 다시 시도하세요.", traceId);
        }

        log.error("[{}] 처리 실패", traceId, e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "요청을 처리하지 못했습니다. 서버 로그에서 traceId 를 확인하세요.", traceId);
    }

    // ── 판정 ──────────────────────────────────────────────────

    /** 예외는 CompletionException 등으로 여러 겹 감싸여 온다 — 끝까지 벗긴다. */
    private Throwable rootCause(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 타입 이름과 메시지를 <b>둘 다</b> 본다.
     *
     * <p>SDK 버전이 오르면 예외 타입이 바뀐다. 타입만 보면 그때 조용히 500 으로 돌아간다.
     */
    private boolean isUnauthorized(Throwable root) {
        String type = root.getClass().getName();
        String message = String.valueOf(root.getMessage());
        return type.contains("Unauthorized")
                || message.contains("401")
                || message.contains("Incorrect API key")
                || message.contains("invalid_api_key");
    }

    private boolean isRateLimited(Throwable root) {
        String type = root.getClass().getName();
        String message = String.valueOf(root.getMessage());
        return type.contains("RateLimit") || message.contains("429");
    }

    private ResponseEntity<Map<String, Object>> body(
            HttpStatus status, String code, String message, String traceId) {
        return ResponseEntity.status(status).body(Map.of(
                "error", code, "message", message, "traceId", traceId));
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
