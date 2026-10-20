package com.oficina.billing.infrastructure.web.security;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

public class CsrfTokenResponseFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("GET".equals(request.getMethod()) && request.getRequestURI().startsWith("/api/v1/")) {
            var token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                response.setHeader("X-CSRF-TOKEN", token.getToken());
            }
        }
        chain.doFilter(request, response);
    }
}
