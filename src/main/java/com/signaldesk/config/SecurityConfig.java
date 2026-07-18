package com.signaldesk.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security is on only under the {@code prod} profile, so local dev stays open. In prod the whole
 * app sits behind HTTP basic auth — one username/password from env
 * ({@code APP_SECURITY_USERNAME} / {@code APP_SECURITY_PASSWORD}) — which stops anyone with the
 * URL from triggering the endpoints that spend API credits. The LINE webhook is left open (LINE
 * can't send auth headers; it's authenticated by its X-Line-Signature HMAC instead).
 */
@Configuration
public class SecurityConfig {

    @Bean
    @Profile("prod")
    SecurityFilterChain secured(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // The LINE webhook can't send basic-auth headers; it authenticates via the
                        // X-Line-Signature HMAC check in LineClient instead.
                        .requestMatchers("/api/line/webhook").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    @Profile("prod")
    UserDetailsService users(@Value("${app.security.username:admin}") String username,
                             @Value("${app.security.password:}") String password) {
        String pw = (password == null || password.isBlank()) ? "changeme" : password;
        UserDetails user = User.withUsername(username).password("{noop}" + pw).roles("USER").build();
        return new InMemoryUserDetailsManager(user);
    }

    /** Non-prod (local dev): no auth, so you can curl the API and webhook freely without a login. */
    @Bean
    @Profile("!prod")
    SecurityFilterChain open(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
