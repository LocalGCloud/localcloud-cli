package com.localcloud.license;

public class LicenseServerConfig {

    private int port;
    private String dbUrl;
    private String dbUser;
    private String dbPassword;
    private String smtpHost;
    private int smtpPort;
    private String smtpUser;
    private String smtpPassword;
    private String smtpFrom;
    private int otpExpiryMinutes;
    private int trialDays;

    private LicenseServerConfig() {}

    public static LicenseServerConfig fromEnvironment() {
        LicenseServerConfig c = new LicenseServerConfig();
        c.port = intEnv("LICENSE_PORT", 9090);
        c.dbUrl = env("LICENSE_DB_URL", "jdbc:postgresql://localhost:5432/localcloud_license");
        c.dbUser = env("LICENSE_DB_USER", "license");
        c.dbPassword = env("LICENSE_DB_PASSWORD", "license");
        c.smtpHost = env("LICENSE_SMTP_HOST", "localhost");
        c.smtpPort = intEnv("LICENSE_SMTP_PORT", 587);
        c.smtpUser = env("LICENSE_SMTP_USER", "");
        c.smtpPassword = env("LICENSE_SMTP_PASSWORD", "");
        c.smtpFrom = env("LICENSE_SMTP_FROM", "noreply@localcloud.dev");
        c.otpExpiryMinutes = intEnv("LICENSE_OTP_EXPIRY_MINUTES", 15);
        c.trialDays = intEnv("LICENSE_TRIAL_DAYS", 14);
        return c;
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return (v != null && !v.isBlank()) ? v : def;
    }

    private static int intEnv(String name, int def) {
        try { return Integer.parseInt(env(name, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    public int getPort() { return port; }
    public String getDbUrl() { return dbUrl; }
    public String getDbUser() { return dbUser; }
    public String getDbPassword() { return dbPassword; }
    public String getSmtpHost() { return smtpHost; }
    public int getSmtpPort() { return smtpPort; }
    public String getSmtpUser() { return smtpUser; }
    public String getSmtpPassword() { return smtpPassword; }
    public String getSmtpFrom() { return smtpFrom; }
    public int getOtpExpiryMinutes() { return otpExpiryMinutes; }
    public int getTrialDays() { return trialDays; }
}
