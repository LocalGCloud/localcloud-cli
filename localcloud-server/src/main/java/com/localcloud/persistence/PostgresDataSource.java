package com.localcloud.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import com.localcloud.config.LocalCloudConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostgresDataSource {
    private static final Logger logger = LoggerFactory.getLogger(PostgresDataSource.class);
    private final HikariDataSource dataSource;

    public PostgresDataSource(LocalCloudConfig config) {
        HikariConfig hikari = new HikariConfig();
        String host = config.getPostgresHost();
        int port = config.getPostgresPort();
        String db = config.getPostgresDatabase();
        hikari.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + db);
        hikari.setUsername(config.getPostgresUser());
        hikari.setPassword(config.getPostgresPassword());
        hikari.setMaximumPoolSize(10);
        hikari.setMinimumIdle(2);
        hikari.setPoolName("localcloud-pg");
        hikari.setConnectionTestQuery("SELECT 1");
        this.dataSource = new HikariDataSource(hikari);
        logger.info("PostgreSQL connection pool initialized: {}:{}/{}", host, port, db);
    }

    public DataSource getDataSource() { return dataSource; }
    public Connection getConnection() throws SQLException { return dataSource.getConnection(); }
    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
            logger.info("PostgreSQL data source closed");
        }
    }
}
