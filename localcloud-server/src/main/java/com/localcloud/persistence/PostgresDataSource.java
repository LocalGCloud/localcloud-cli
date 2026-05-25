package com.localcloud.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
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
    private final String host;
    private final int port;
    private final String username;
    private final String password;

    public PostgresDataSource(LocalCloudConfig config) {
        HikariConfig hikari = new HikariConfig();
        this.host = config.getPostgresHost();
        this.port = config.getPostgresPort();
        String db = config.getPostgresDatabase();
        this.username = config.getPostgresUser();
        this.password = config.getPostgresPassword();
        hikari.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + db);
        hikari.setUsername(username);
        hikari.setPassword(password);
        hikari.setMaximumPoolSize(10);
        hikari.setMinimumIdle(2);
        hikari.setPoolName("localcloud-pg");
        hikari.setConnectionTestQuery("SELECT 1");
        this.dataSource = new HikariDataSource(hikari);
        logger.info("PostgreSQL connection pool initialized: {}:{}/{}", host, port, db);
    }

    public DataSource getDataSource() { return dataSource; }
    public Connection getConnection() throws SQLException { return dataSource.getConnection(); }
    public Connection getConnection(String databaseName) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/" + databaseName,
                username, password);
    }
    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
            logger.info("PostgreSQL data source closed");
        }
    }
}
