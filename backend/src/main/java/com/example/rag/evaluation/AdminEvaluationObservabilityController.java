package com.example.rag.evaluation;

import com.example.rag.evaluation.EvaluationContracts.GateView;
import com.example.rag.evaluation.EvaluationContracts.ObservabilityView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/evaluations")
class AdminEvaluationObservabilityController {

    private final EvaluationObservabilityService observability;

    AdminEvaluationObservabilityController(
            EvaluationObservabilityService observability
    ) {
        this.observability = observability;
    }

    @GetMapping("/observability")
    ObservabilityView observability() {
        return observability.observability();
    }

    @GetMapping("/gates")
    List<GateView> gates() {
        return observability.gates();
    }
}
