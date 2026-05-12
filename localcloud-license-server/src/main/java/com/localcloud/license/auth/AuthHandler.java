package com.localcloud.license.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.localcloud.license.email.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

@ProducesJson
public class AuthHandler {

    private static final Logger logger = LoggerFactory.getLogger(AuthHandler.class);
    private final AuthRepository authRepo;
    private final OtpService otpService;
    private final EmailService emailService;
    private final SessionRepository sessionRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthHandler(AuthRepository authRepo, OtpService otpService, EmailService emailService,
                       SessionRepository sessionRepo) {
        this.authRepo = authRepo;
        this.otpService = otpService;
        this.emailService = emailService;
        this.sessionRepo = sessionRepo;
    }

    @Post("/register")
    public HttpResponse register(@RequestObject Map<String, String> body) {
        String email = body.get("email");
        if (email == null || !email.contains("@")) {
            return error(HttpStatus.BAD_REQUEST, "Invalid email address");
        }
        try {
            authRepo.createUser(email);
            String otp = otpService.generateOtp(email);
            emailService.sendOtp(email, otp);
            return ok(Map.of("message", "Verification code sent to " + email));
        } catch (Exception e) {
            logger.error("Registration failed for {}: {}", email, e.getMessage());
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Registration failed");
        }
    }

    @Post("/verify")
    public HttpResponse verify(@RequestObject Map<String, String> body) {
        String email = body.get("email");
        String code = body.get("code");
        if (email == null || code == null) {
            return error(HttpStatus.BAD_REQUEST, "email and code required");
        }
        try {
            boolean valid = otpService.verifyOtp(email, code);
            if (!valid) return error(HttpStatus.UNAUTHORIZED, "Invalid or expired verification code");
            authRepo.markEmailVerified(email);
            UUID userId = authRepo.getUserId(email);
            String sessionToken = sessionRepo.createSession(userId);
            return ok(Map.of(
                "message", "Email verified successfully",
                "session_token", sessionToken,
                "expires_in_seconds", 3600
            ));
        } catch (Exception e) {
            logger.error("Verification failed for {}: {}", email, e.getMessage());
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Verification failed");
        }
    }

    private HttpResponse ok(Object body) {
        try {
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, mapper.writeValueAsString(body));
        } catch (Exception e) { return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    private HttpResponse error(HttpStatus status, String message) {
        try {
            return HttpResponse.of(status, MediaType.JSON_UTF_8,
                mapper.writeValueAsString(Map.of("error", message)));
        } catch (Exception e) { return HttpResponse.of(status); }
    }
}
