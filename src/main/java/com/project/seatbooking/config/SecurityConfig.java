package com.project.seatbooking.config;

import com.project.seatbooking.repository.UserRepository;
import com.project.seatbooking.service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService userDetailsService(UserRepository users) {
        return username -> users.find(username)
                .map(user -> org.springframework.security.core.userdetails.User.withUsername(user.getUsername())
                        .password(user.getPasswordHash()).authorities(user.getRole()).build())
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }

    @Bean SecurityFilterChain security(HttpSecurity http, TokenService tokens) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health/**", "/metrics", "/auth/token").permitAll()
                        .requestMatchers(HttpMethod.POST, "/shows").hasAuthority("ROLE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/shows/*").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, ex) ->
                        response.sendError(HttpStatus.UNAUTHORIZED.value())))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .addFilterBefore(new BearerFilter(tokens), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    static class BearerFilter extends OncePerRequestFilter {
        private final TokenService tokens;
        BearerFilter(TokenService tokens) { this.tokens = tokens; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                   FilterChain chain) throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header != null) {
                if (!header.startsWith("Bearer ")) { response.sendError(HttpStatus.UNAUTHORIZED.value()); return; }
                try {
                    var user = tokens.authenticate(header.substring(7));
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                            user.getUsername(), null, List.of(new SimpleGrantedAuthority(user.getRole()))));
                } catch (DataAccessException ex) { throw ex; }
                catch (Exception ex) { response.sendError(HttpStatus.UNAUTHORIZED.value()); return; }
            }
            chain.doFilter(request, response);
        }
    }
}
