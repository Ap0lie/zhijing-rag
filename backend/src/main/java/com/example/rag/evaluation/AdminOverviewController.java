package com.example.rag.evaluation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
class AdminOverviewController {

    private final AdminOverviewService service;

    AdminOverviewController(AdminOverviewService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    AdminOverviewResponse overview() {
        return service.overview();
    }
}
