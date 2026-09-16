package org.yuktisetu.driveservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.yuktisetu.core.web.YuktiSetuCorsSource;

/**
 * Delegates to the shared policy in core rather than hand-copying the origin /
 * method / header lists a fourth time -- see YuktiSetuCorsSource's javadoc.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        return YuktiSetuCorsSource.defaultSource();
    }
}
