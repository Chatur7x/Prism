package com.prism.llm;

import com.prism.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Makes the active provider unmistakable at startup.
 *
 * <p>PRISM ships two providers behind one interface. The offline one returns
 * canned responses so the pipeline can be tested with no credentials and no
 * spend. That is a genuine benefit and also a trap: a report generated against
 * the fixture is arithmetically valid and completely silent about the fact that
 * no model was involved. The failure mode is not a crash, it is a number that
 * reads like evidence.
 *
 * <p>So the mode is stated three ways, because each fails differently:
 *
 * <ul>
 *   <li>a startup banner, which a reader sees in the first thirty log lines;</li>
 *   <li>{@code testMode} on the provider description, which reaches the admin API
 *       and therefore anything that archives it;</li>
 *   <li>the caveat on the evaluation report, so the artifact of record carries it
 *       even if the log has scrolled away.</li>
 * </ul>
 *
 * <p><b>There is no fallback from real to offline.</b> The offline bean requires
 * {@code prism.llm.provider=fake} explicitly, and the real bean is the
 * {@code matchIfMissing} default, so an unset or misspelt provider value selects
 * the real client and fails loudly rather than quietly degrading to the fixture.
 * {@code LlmProviderModeTest} asserts both directions, because a future edit that
 * added a try/catch around provider construction would otherwise reintroduce the
 * silent downgrade that this whole class exists to rule out.
 */
@Component
public class LlmModeReporter {

    private static final Logger log = LoggerFactory.getLogger(LlmModeReporter.class);

    private final LlmClient client;
    private final LlmProperties props;

    public LlmModeReporter(LlmClient client, LlmProperties props) {
        this.client = client;
        this.props = props;
    }

    /** True when the deterministic fixture is active. Never true for a real provider. */
    public boolean isTestMode() {
        return "fake".equalsIgnoreCase(String.valueOf(props.provider()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportMode() {
        if (isTestMode()) {
            log.warn("");
            log.warn("================================================================");
            log.warn("  LLM PROVIDER = fake   --   FAKE / TEST MODE");
            log.warn("  Deterministic fixture. NO MODEL IS BEING CALLED.");
            log.warn("  Everything downstream is genuinely computed, but it is");
            log.warn("  computed from canned responses. Any accuracy, precision or");
            log.warn("  recall figure produced in this mode describes the fixture and");
            log.warn("  the pipeline, and says NOTHING about model quality.");
            log.warn("================================================================");
            log.warn("");
            return;
        }

        // The real provider. Its configuration is logged because a misconfigured
        // endpoint is otherwise indistinguishable from a bad prompt.
        log.info("LLM provider: {} (real inference)", client.providerName());
        log.info("  extract={} judge={} debate={} synthesis={} chat={}",
                props.extractModel(), props.judgeModel(), props.debateModel(),
                props.synthesisModel(), props.chatModel());
        log.info("  temperature={} maxTokens={} maxRetries={} timeout={}s",
                props.temperature(), props.maxTokens(), props.maxRetries(), props.timeoutSeconds());
    }
}