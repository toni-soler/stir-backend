package org.stir.observability;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebObservabilityConfig implements WebMvcConfigurer {
    private final CorrelationMdcInterceptor interceptor;
    public WebObservabilityConfig(CorrelationMdcInterceptor interceptor) { this.interceptor = interceptor; }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(interceptor); }
}
