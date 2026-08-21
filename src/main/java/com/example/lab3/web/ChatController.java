package com.example.lab3.web;

import java.time.Duration;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.lab3.chat.AnswerDto;
import com.example.lab3.chat.HelpDeskService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Phase 6 — 구조화 응답 API 와 SSE.
 *
 * <p><b>사용자 식별에 대하여</b> — 원 설계는 {@code Principal} 로 인증 사용자를 받는다.
 * 인증·인가(Phase 7)는 이번 실습 범위 밖이므로 {@code X-User-Id} 헤더로 대신한다.
 * Security 를 붙이면 이 헤더를 {@code Principal#getName} 으로 바꾸기만 하면 된다.
 * 헤더를 신뢰하는 구조이므로 <b>운영에 그대로 쓰면 안 된다</b>.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final HelpDeskService service;

    public ChatController(HelpDeskService service) {
        this.service = service;
    }

    /** 스트리밍 토큰 한 조각. 공백 보존을 위해 JSON 으로 감싼다. */
    public record Token(String t) {}

    /** 질의 요청 바디 */
    public record AskRequest(String question, String sessionId) {

        String sessionOrDefault() {
            return (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        }
    }

    /** 동기 — 구조화 응답 */
    @PostMapping
    public AnswerDto ask(@RequestBody AskRequest request,
                         @RequestHeader(name = "X-User-Id", defaultValue = "user1") String userId) {
        return service.ask(request.question(), userId, request.sessionOrDefault());
    }

    /**
     * 스트리밍 — 토큰을 {@code token} 이벤트로 흘리고,
     * 마지막에 {@code sources} 이벤트로 출처를 함께 보낸다.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(
            @RequestBody AskRequest request,
            @RequestHeader(name = "X-User-Id", defaultValue = "user1") String userId) {

        String session = request.sessionOrDefault();

        // 토큰을 원시 문자열로 실으면 안 된다.
        //
        // SSE 규격은 "data:" 뒤의 선행 공백 하나를 제거한다. 토크나이저는 " 반품은" 처럼
        // 앞 공백이 붙은 토큰을 내보내므로, 그대로 실으면 공백이 매번 사라져
        // 화면에 "단순변심반품은" 처럼 붙어 나온다.
        // JSON 으로 감싸면 공백이 문자열 안에 있어 규격의 영향을 받지 않는다.
        Flux<ServerSentEvent<Object>> tokens = service.stream(request.question(), userId, session)
                .map(chunk -> ServerSentEvent.builder((Object) new Token(chunk))
                        .event("token").build());

        Mono<ServerSentEvent<Object>> sources = Mono.fromCallable(() -> {
            List<AnswerDto.Source> found = service.lastSources(userId, session);
            return ServerSentEvent.builder((Object) found).event("sources").build();
        });

        return tokens.concatWith(sources).timeout(Duration.ofSeconds(60));
    }
}
