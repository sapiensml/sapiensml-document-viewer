package com.sapiensml.viewer.web;

import java.time.Duration;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Static files of the React build. Vite names assets by content hash, so they can be cached for good. */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
            .addResourceLocations("classpath:/static/assets/")
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        // PDF.js worker, fonts and decoders keep their names across builds
        registry.addResourceHandler("/pdfjs/**")
            .addResourceLocations("classpath:/static/pdfjs/")
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic());
    }

}
