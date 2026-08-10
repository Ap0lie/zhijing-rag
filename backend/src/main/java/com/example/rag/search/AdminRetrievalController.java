package com.example.rag.search;

import com.example.rag.search.RetrievalConfigurationContracts.CreateRetrievalProfileRequest;
import com.example.rag.search.RetrievalConfigurationContracts.RetrievalConfigurationResponse;
import com.example.rag.search.RetrievalConfigurationContracts.RetrievalProfileView;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/retrieval")
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class AdminRetrievalController {

    private final RetrievalConfigurationRepository configurations;

    AdminRetrievalController(RetrievalConfigurationRepository configurations) {
        this.configurations = configurations;
    }

    @GetMapping("/configuration")
    RetrievalConfigurationResponse configuration() {
        return configurations.configuration();
    }

    @PostMapping("/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    RetrievalProfileView createProfile(
            @Valid @RequestBody CreateRetrievalProfileRequest request
    ) {
        return configurations.create(request);
    }
}
