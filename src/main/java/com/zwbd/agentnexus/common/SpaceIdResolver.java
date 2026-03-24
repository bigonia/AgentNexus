package com.zwbd.agentnexus.common;

import com.zwbd.agentnexus.common.web.GlobalContext;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * @Author: wnli
 * @Date: 2025/11/24 15:39
 * @Desc:
 */
@Component
public class SpaceIdResolver implements CurrentTenantIdentifierResolver {

    @Override
    public String resolveCurrentTenantIdentifier() {
        String spaceId = GlobalContext.getSpaceId();
        return (spaceId != null) ? spaceId : "default_space";
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
