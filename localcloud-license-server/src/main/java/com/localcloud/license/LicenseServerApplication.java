package com.localcloud.license;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.localcloud.license.auth.AuthHandler;
import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.auth.OtpService;
import com.localcloud.license.auth.SessionAuthDecorator;
import com.localcloud.license.auth.SessionRepository;
import com.localcloud.license.db.LicenseDatabase;
import com.localcloud.license.db.SchemaInitializer;
import com.localcloud.license.email.EmailService;
import com.localcloud.license.keys.ApiKeyHandler;
import com.localcloud.license.keys.ApiKeyRepository;
import com.localcloud.license.trial.TrialHandler;
import com.localcloud.license.trial.TrialRepository;
import com.localcloud.license.validation.DeviceTracker;
import com.localcloud.license.validation.LicenseValidationHandler;
import com.localcloud.license.validation.LicenseValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LicenseServerApplication {

    private static final Logger logger = LoggerFactory.getLogger(LicenseServerApplication.class);

    public static void main(String[] args) throws Exception {
        LicenseServerConfig config = LicenseServerConfig.fromEnvironment();

        LicenseDatabase db = new LicenseDatabase(config);
        new SchemaInitializer(db.getDataSource()).initialize();

        var authRepo = new AuthRepository(db.getDataSource());
        var otpService = new OtpService(db.getDataSource(), config.getOtpExpiryMinutes());
        var emailService = new EmailService(config);
        var sessionRepo = new SessionRepository(db.getDataSource());
        var keyRepo = new ApiKeyRepository(db.getDataSource());
        var deviceTracker = new DeviceTracker(db.getDataSource());
        var licenseValidator = new LicenseValidator(keyRepo, authRepo, deviceTracker);
        var trialRepo = new TrialRepository(db.getDataSource(), config.getTrialDays());

        ServerBuilder sb = Server.builder();
        sb.http(config.getPort());

        sb.annotatedService("/auth", new AuthHandler(authRepo, otpService, emailService, sessionRepo));
        sb.annotatedService("/keys", new ApiKeyHandler(keyRepo),
            new SessionAuthDecorator(sessionRepo));
        sb.annotatedService("/license", new LicenseValidationHandler(licenseValidator));
        sb.annotatedService("/trial", new TrialHandler(trialRepo, authRepo, keyRepo));

        sb.service("/health", (ctx, req) ->
            HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{\"status\":\"ok\"}"));

        Server server = sb.build();
        server.start().join();
        logger.info("LocalCloud License Server started on port {}", config.getPort());
        server.blockUntilShutdown();
    }
}
