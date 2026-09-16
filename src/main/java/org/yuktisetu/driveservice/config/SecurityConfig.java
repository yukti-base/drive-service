package org.yuktisetu.driveservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.yuktisetu.core.security.JwtAuthenticationFilter;
import org.yuktisetu.core.security.JwtTokenVerifier;
import org.yuktisetu.core.security.RestAccessDeniedHandler;
import org.yuktisetu.core.security.RestAuthenticationEntryPoint;

/**
 * Same chain as admin-service. The JwtTokenVerifier bean comes from core's
 * JwtPublicKeyConfig, which is picked up because the application class scans
 * org.yuktisetu.core -- this service holds no private key and can only verify.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtTokenVerifier tokenVerifier) throws Exception {

        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    // CORS preflight must never require a JWT.
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(new RestAuthenticationEntryPoint())
                    .accessDeniedHandler(new RestAccessDeniedHandler())
            )
            .addFilterBefore(new JwtAuthenticationFilter(tokenVerifier),
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
