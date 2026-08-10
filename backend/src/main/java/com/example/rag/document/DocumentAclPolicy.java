package com.example.rag.document;

import com.example.rag.persistence.DocumentAclEntryRepository;
import com.example.rag.persistence.DocumentEntity;
import com.example.rag.persistence.DocumentVisibility;
import com.example.rag.persistence.UserRole;
import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.stereotype.Component;

@Component
public class DocumentAclPolicy {

    private final DocumentAclEntryRepository aclEntries;

    public DocumentAclPolicy(DocumentAclEntryRepository aclEntries) {
        this.aclEntries = aclEntries;
    }

    public boolean canRead(DocumentEntity document, PlatformUserPrincipal user) {
        if (document.getDeletedAt() != null) {
            return false;
        }
        if (user.role() == UserRole.ADMIN) {
            return true;
        }
        if (document.getVisibility() == DocumentVisibility.ALL_USERS) {
            return true;
        }
        if (document.getOwner().getId().equals(user.id())) {
            return true;
        }
        return aclEntries.existsByDocumentIdAndUserId(document.getId(), user.id());
    }
}
