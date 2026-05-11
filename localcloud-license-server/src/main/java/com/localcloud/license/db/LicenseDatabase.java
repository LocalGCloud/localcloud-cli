package com.localcloud.license.db;

import com.localcloud.license.LicenseServerConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

public class LicenseDatabase {

    private final HikariDataSource pool;

    public LicenseDatabase(LicenseServerConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.getDbUrl());
        hc.setUsername(config.getDbUser());
        hc.setPassword(config.getDbPassword());
        hc.setMaximumPoolSize(10);
        hc.setMinimumIdle(2);
        hc.setConnectionTimeout(30_000);
        this.pool = new HikariDataSource(hc);
    }

    public DataSource getDataSource() {
        return pool;
    }

    public void close() {
        pool.close();
    }
}
