package com.zwbd.agentnexus.common;

import com.zwbd.agentnexus.common.web.GlobalContext;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Hibernate multi-tenant resolver: isolates data by user identity.
 * <p>
 * The current user is resolved from the JWT SecurityContext via
 * {@link GlobalContext#getUserId()}. Falls back to {@code "default"}
 * for unauthenticated requests (public endpoints).
 */
@Component
public class UserIdResolver implements CurrentTenantIdentifierResolver {

    @Override
    public String resolveCurrentTenantIdentifier() {
        String userId = GlobalContext.getUserId();
        return (userId != null && !userId.isBlank()) ? userId : GlobalContext.DEFAULT_USER_ID;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
