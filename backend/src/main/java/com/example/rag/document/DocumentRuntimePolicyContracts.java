package com.example.rag.document;

import com.example.rag.pipeline.ParserProviderKind;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

final class DocumentRuntimePolicyContracts {

    private DocumentRuntimePolicyContracts() {
    }

    record ChangePolicyRequest(
            ParserProviderKind parserProvider,
            @NotNull DocumentRuntimePolicyService.PolicyAction action,
            @NotNull String confirmation,
            @NotNull @Size(min = 8, max = 500) String reason
    ) {
    }
}
