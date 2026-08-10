package com.example.rag.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getToken(), token.getHeaderName());
    }

    @GetMapping("/me")
    CurrentUserResponse me(@AuthenticationPrincipal PlatformUserPrincipal principal) {
        return CurrentUserResponse.from(principal);
    }

    public record CsrfResponse(String token, String headerName) {
    }
}
