package com.prism.claims;

import com.prism.config.PrismTuningProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the deterministic engines as beans.
 *
 * <p>Each engine takes its configuration as a constructor argument and holds no
 * Spring state, so it stays unit-testable in isolation while still being
 * injectable where configuration matters.
 */
@Configuration
public class DeterministicEngineConfig {

    @Bean
    public ClaimRuleEngine claimRuleEngine(PrismTuningProperties tuning) {
        return new ClaimRuleEngine(tuning.rules());
    }
}
