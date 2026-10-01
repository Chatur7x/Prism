package com.prism.chat;

import com.prism.corpus.CorpusAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Grounded chat. Every answer carries backend-validated citations. */
@RestController
@RequestMapping("/api/chat")
@Tag(name = "Grounded chat", description = "Corpus-scoped question answering with validated citations")
public class ChatController {

    private final ChatService chat;
    private final CorpusAccessService access;

    public ChatController(ChatService chat, CorpusAccessService access) {
        this.chat = chat;
        this.access = access;
    }

    public record CreateSessionRequest(@NotNull Long corpusId, @Size(max = 300) String title) {
    }

    public record AskRequest(@NotBlank @Size(max = 500) String question) {
    }

    public record SessionResponse(Long id, Long corpusId, String title, Instant createdAt,
                                  Instant updatedAt, Instant lastMessageAt, int messageCount) {
        static SessionResponse from(ChatSession s) {
            return new SessionResponse(s.getId(), s.getCorpus().getId(), s.getTitle(),
                    s.getCreatedAt(), s.getUpdatedAt(), s.getLastMessageAt(), s.getMessageCount());
        }
    }

    public record MessageResponse(Long id, String role, String content, boolean grounded,
                                  boolean insufficientEvidence, int retrievalCount, String model,
                                  String promptVersion, Long latencyMs, Instant createdAt,
                                  List<ChatService.Citation> citations) {

        static MessageResponse from(ChatMessage m, List<ChatService.Citation> citations) {
            return new MessageResponse(m.getId(), m.getRole().name(), m.getContent(), m.isGrounded(),
                    m.isInsufficientEvidence(), m.getRetrievalCount(), m.getModel(),
                    m.getPromptVersion(), m.getLatencyMs(), m.getCreatedAt(), citations);
        }
    }

    @PostMapping("/sessions")
    @Operation(summary = "Open a session, permanently bound to one corpus")
    public ResponseEntity<SessionResponse> create(@Valid @RequestBody CreateSessionRequest request) {
        Long userId = access.requireCurrentUserId();
        ChatSession session = chat.createSession(userId, request.corpusId(), request.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(SessionResponse.from(session));
    }

    @GetMapping("/sessions")
    @Operation(summary = "List the caller's sessions")
    public List<SessionResponse> list() {
        Long userId = access.requireCurrentUserId();
        return chat.listSessions(userId).stream().map(SessionResponse::from).toList();
    }

    @GetMapping("/sessions/{id}")
    @Operation(summary = "Session with its full message history")
    public Map<String, Object> get(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        ChatSession session = chat.getSession(userId, id);
        List<MessageResponse> messages = chat.history(userId, id).stream()
                .map(m -> MessageResponse.from(m, decodeCitations(m)))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session", SessionResponse.from(session));
        body.put("messages", messages);
        return body;
    }

    @PostMapping("/sessions/{id}/messages")
    @Operation(summary = "Ask a grounded question",
            description = "The backend refuses without consulting a model when the corpus cannot answer. "
                    + "A response whose citations do not resolve is discarded and reported as such.")
    public Map<String, Object> ask(@PathVariable Long id, @Valid @RequestBody AskRequest request) {
        Long userId = access.requireCurrentUserId();
        ChatService.ChatResponse response = chat.ask(userId, id, request.question());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageId", response.messageId());
        body.put("answer", response.answer());
        body.put("citations", response.citations());
        body.put("grounded", response.grounded());
        body.put("insufficientEvidence", response.insufficientEvidence());
        body.put("retrievalCount", response.retrievalCount());
        body.put("model", response.model());
        return body;
    }

    private List<ChatService.Citation> decodeCitations(ChatMessage message) {
        String json = message.getCitationsJson();
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        try {
            return List.of(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, ChatService.Citation[].class));
        } catch (Exception ex) {
            return List.of();
        }
    }
}
