package com.example.rag.security;

import com.example.rag.persistence.UserRepository;
import com.example.rag.user.AccountPolicy;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public DatabaseUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return users.findByUsername(AccountPolicy.normalizeUsername(username))
                .map(PlatformUserPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
