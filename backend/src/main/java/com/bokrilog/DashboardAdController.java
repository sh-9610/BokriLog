package com.bokrilog;

import com.bokrilog.engagement.DashboardAdApi;
import com.bokrilog.identity.AccountAccessApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class DashboardAdController {

    static final String DASHBOARD_AD_PATH = AuthSecurityConfig.ENGAGEMENT_PATH + "/dashboard-ad";

    record Configuration(String adUnitPath) {}

    private final DashboardAdApi ads;
    private final AccountAccessApi accounts;

    DashboardAdController(DashboardAdApi ads, AccountAccessApi accounts) {
        this.ads = ads;
        this.accounts = accounts;
    }

    @GetMapping(DASHBOARD_AD_PATH)
    @Nullable ResponseEntity<?> configuration(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (ControllerSupport.allowedUser(this.accounts) == null) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return null;
        }
        try {
            String path = this.ads.adUnitPath();
            return path == null
                    ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
                    : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Configuration(path));
        } catch (RuntimeException failure) {
            return ControllerSupport.error(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorHandler.CODE_INTERNAL_ERROR,
                    ApiErrorHandler.MESSAGE_INTERNAL_ERROR, request);
        }
    }
}
