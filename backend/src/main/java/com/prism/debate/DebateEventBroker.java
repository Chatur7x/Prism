package com.prism.debate;

import com.prism.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory SSE fan-out for debate events.
 *
 * <p><b>Scope limitation, stated plainly.</b> This broker is per-instance. Two
 * replicas behind a load balancer would each hold only the emitters connected
 * to them, so a client could miss events emitted on the other instance. It is
 * correct for the single-instance deployment this project targets; a
 * multi-instance deployment needs a shared broker (Redis pub/sub or similar)
 * before this class is used unchanged. The README records the same caveat.
 *
 * <p><b>Leak prevention.</b> Every emitter is registered in a per-debate set and
 * removed on completion, error, or timeout. A heartbeat task reaps emitters
 * that fail to send, which is what stops a client that vanished without a clean
 * close from pinning a thread forever.
 */
@Component
public class DebateEventBroker {

    private static final Logger log = LoggerFactory.getLogger(DebateEventBroker.class);

    /** 30 minutes: a debate should not stay open longer than this regardless. */
    private static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final long HEARTBEAT_SECONDS = 20;

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "prism-sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /** Event names the frontend can switch on. */
    public static final String EVENT_ARGUMENT = "argument";
    public static final String EVENT_VERDICT = "verdict";
    public static final String EVENT_PHASE = "phase";
    public static final String EVENT_ROUND_COMPLETE = "round_complete";
    public static final String EVENT_AWAITING_CHAIR = "awaiting_chair";
    public static final String EVENT_SYNTHESIS_READY = "synthesis_ready";
    public static final String EVENT_ERROR = "error";
    public static final String EVENT_HEARTBEAT = "heartbeat";

    public SseEmitter subscribe(Long debateId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        emitters.computeIfAbsent(debateId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        AtomicBoolean closed = new AtomicBoolean(false);
        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                remove(debateId, emitter);
            }
        };

        emitter.onCompletion(cleanup::run);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(ex -> cleanup.run());

        // Send the current state immediately so a client that connects mid-round
        // is not left staring at an empty panel.
        try {
            emitter.send(SseEmitter.event().name(EVENT_PHASE)
                    .data(Map.of("debateId", debateId, "subscribed", true)));
        } catch (IOException ex) {
            cleanup.run();
        }
        return emitter;
    }

    /** Publishes an event to every subscriber of a debate. */
    public void publish(Long debateId, String eventName, Object payload) {
        List<SseEmitter> targets = emitters.get(debateId);
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException ex) {
                // The client is gone. Drop the emitter rather than retrying
                // against a dead connection.
                log.debug("Dropping SSE emitter for debate {}: {}", debateId, ex.getMessage());
                remove(debateId, emitter);
            }
        }
    }

    public int subscriberCount(Long debateId) {
        List<SseEmitter> targets = emitters.get(debateId);
        return targets == null ? 0 : targets.size();
    }

    /** Closes every emitter for a debate, e.g. once it reaches a terminal state. */
    public void completeAll(Long debateId) {
        List<SseEmitter> targets = emitters.remove(debateId);
        if (targets == null) {
            return;
        }
        for (SseEmitter emitter : targets) {
            try {
                emitter.complete();
            } catch (RuntimeException ex) {
                log.debug("Error completing SSE emitter for debate {}: {}", debateId, ex.getMessage());
            }
        }
    }

    /**
     * Starts the heartbeat. Kept as an explicit method so tests can run without
     * a background thread.
     */
    public ScheduledFuture<?> startHeartbeat() {
        return heartbeat.scheduleAtFixedRate(() -> {
            for (Map.Entry<Long, List<SseEmitter>> entry : emitters.entrySet()) {
                for (SseEmitter emitter : entry.getValue()) {
                    try {
                        emitter.send(SseEmitter.event().name(EVENT_HEARTBEAT)
                                .data(Map.of("at", System.currentTimeMillis())));
                    } catch (IOException | IllegalStateException ex) {
                        remove(entry.getKey(), emitter);
                    }
                }
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    private void remove(Long debateId, SseEmitter emitter) {
        List<SseEmitter> targets = emitters.get(debateId);
        if (targets == null) {
            return;
        }
        targets.remove(emitter);
        if (targets.isEmpty()) {
            // Avoid retaining an empty list per finished debate forever.
            emitters.remove(debateId, targets);
        }
    }

    /** Terminates the heartbeat thread. Called on application shutdown. */
    public void shutdown() {
        heartbeat.shutdownNow();
    }
}
