package dev.sumituppal.pager.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link PagerProperties} as a Spring bean and enables its
 * {@code @ConfigurationProperties} binding.
 *
 * <p>Kept as its own configuration class (rather than annotating
 * {@link dev.sumituppal.pager.PagerApplication}) so config concerns stay
 * out of the main entry point.
 */
@Configuration
@EnableConfigurationProperties(PagerProperties.class)
public class PagerConfigurationRegistrar {
}