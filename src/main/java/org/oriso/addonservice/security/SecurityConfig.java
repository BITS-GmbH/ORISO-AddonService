package org.oriso.addonservice.security;

import org.oriso.addonservice.token.AddonTokenIssuer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  /** Add-on backends call the host API with the AddonService's own tokens, not Keycloak's. */
  @Bean
  @Order(1)
  SecurityFilterChain hostApi(HttpSecurity http, AddonTokenIssuer tokens) throws Exception {
    http.securityMatcher("/addons/host/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(tokens.decoder())));
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    // Only bearer tokens in the Authorization header are accepted, never cookies,
    // so the double-submit CSRF check of the other services is not needed here.
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/actuator/health/**", "/actuator/health")
                    .permitAll()
                    .requestMatchers("/addons/.well-known/jwks.json")
                    .permitAll()
                    // Add-on bundles and their assets carry no user data and are loaded
                    // with import(), which cannot send an Authorization header.
                    .requestMatchers("/addons/*/ui/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
    return http.build();
  }
}
