package com.example.rag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.ObjectPostProcessor;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import java.io.IOException;
import java.util.Map;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            SessionRegistry sessionRegistry
    ) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info", "/api/v1/auth/csrf").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll()
                )
                .formLogin(form -> form
                        .loginProcessingUrl("/api/v1/auth/login")
                        .successHandler((request, response, authentication) -> {
                            var principal = (PlatformUserPrincipal) authentication.getPrincipal();
                            writeJson(response, HttpServletResponse.SC_OK,
                                    CurrentUserResponse.from(principal), objectMapper);
                        })
                        .failureHandler((request, response, exception) -> writeJson(
                                response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                Map.of("code", "INVALID_CREDENTIALS", "message", "用户名或密码不正确"),
                                objectMapper
                        ))
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> writeJson(
                                response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                Map.of("code", "UNAUTHENTICATED", "message", "请先登录"),
                                objectMapper
                        ))
                        .accessDeniedHandler((request, response, exception) -> writeJson(
                                response,
                                HttpServletResponse.SC_FORBIDDEN,
                                Map.of("code", "ACCESS_DENIED", "message", "没有权限执行此操作"),
                                objectMapper
                        ))
                )
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers.addObjectPostProcessor(
                        new ObjectPostProcessor<HeaderWriterFilter>() {
                            @Override
                            public <O extends HeaderWriterFilter> O postProcess(O filter) {
                                filter.setShouldWriteHeadersEagerly(true);
                                return filter;
                            }
                        }
                ))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId())
                        .maximumSessions(-1)
                        .sessionRegistry(sessionRegistry)
                        .expiredSessionStrategy(event -> writeJson(
                                event.getResponse(),
                                HttpServletResponse.SC_UNAUTHORIZED,
                                Map.of("code", "SESSION_EXPIRED", "message", "登录状态已失效，请重新登录"),
                                objectMapper
                        ))
                );

        return http.build();
    }

    private static void writeJson(
            HttpServletResponse response,
            int status,
            Object body,
            ObjectMapper objectMapper
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
