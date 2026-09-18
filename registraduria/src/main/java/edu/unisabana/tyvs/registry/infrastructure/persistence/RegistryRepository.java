package edu.unisabana.tyvs.registry.infrastructure.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.unisabana.tyvs.registry.application.port.out.RegistryRepositoryPort;
import java.sql.*;
import java.util.Optional;
import javax.sql.DataSource;

public class RegistryRepository implements RegistryRepositoryPort {
    private final DataSource dataSource;

    public RegistryRepository(String jdbcUrl) {
        this(jdbcUrl, "", "");
    }

    public RegistryRepository(String jdbcUrl, String username, String password) {
        // Pool de conexiones (HikariCP) en lugar de abrir una conexion nueva
        // por operacion con DriverManager. Ver README, seccion Observabilidad.
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);
        this.dataSource = new HikariDataSource(config);
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void initSchema() throws Exception {
        final String ddl = "CREATE TABLE IF NOT EXISTS registry(" +
                " id INT PRIMARY KEY," +
                " name VARCHAR(100) NOT NULL," +
                " age INT NOT NULL," +
                " is_alive BOOLEAN NOT NULL" +
                ");";
        try (Connection con = getConnection(); Statement st = con.createStatement()) {
            st.execute(ddl);
        }
    }

    @Override
    public boolean existsById(int id) throws Exception {
        final String sql = "SELECT 1 FROM registry WHERE id = ?";
        try (Connection con = getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public void save(int id, String name, int age, boolean isAlive) throws Exception {
        final String sql = "INSERT INTO registry(id, name, age, is_alive) VALUES(?, ?, ?, ?)";
        try (Connection con = getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            boolean prev = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                ps.setInt(1, id);
                ps.setString(2, name);
                ps.setInt(3, age);
                ps.setBoolean(4, isAlive);
                ps.executeUpdate();
                con.commit();
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(prev);
            }
        }
    }

    @Override
    public Optional<RegistryRecord> findById(int id) throws Exception {
        final String sql = "SELECT id, name, age, is_alive FROM registry WHERE id = ?";
        try (Connection con = getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next())
                    return Optional.empty();
                return Optional.of(new RegistryRecord(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getInt("age"),
                        rs.getBoolean("is_alive")));
            }
        }
    }

    @Override
    public void deleteAll() throws Exception {
        try (Connection con = getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM registry");
        }
    }
}
