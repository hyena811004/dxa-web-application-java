package com.sdl.webapp.common.impl.contextengine;

import com.sdl.webapp.common.api.contextengine.ContextClaims;
import com.sdl.webapp.common.api.contextengine.ContextClaimsProvider;
import com.sdl.webapp.common.api.contextengine.ContextEngine;
import com.sdl.webapp.common.exceptions.DxaException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@Scope(value = "request")
public class ContextEngineImpl implements ContextEngine {

    @Autowired
    private ContextClaimsProvider provider;

    @Getter(lazy = true)
    private final Map<String, Object> claims = claims();

    @Autowired
    private DeviceFamiliesEvaluator deviceFamiliesEvaluator;

    @Getter(lazy = true)
    private final String deviceFamily = deviceFamily();

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends ContextClaims> T getClaims(Class<T> cls) {
        try {
            T result = cls.newInstance();
            result.setClaims(getClaims());
            return result;
        } catch (InstantiationException | IllegalAccessException e) {
            log.error("Exception during getClaims()", e);
            return null;
        }
    }

    private Map<String, Object> claims() {
        try {
            return provider.getContextClaims(null);
        } catch (DxaException e) {
            log.error("Exception getting claims from provider {}", provider, e);
            return null;
        }
    }

    private String deviceFamily() {
        String result = provider.getDeviceFamily();
        if (result != null) {
            return result;
        }

        result = deviceFamiliesEvaluator.defineDeviceFamily(getClaims());
        if (result != null) {
            return result;
        }

        return deviceFamiliesEvaluator.fallbackDeviceFamily(getClaims(DeviceClaims.class));
    }


}
