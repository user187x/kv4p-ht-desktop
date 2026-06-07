/*
kv4p HT desktop port - GPLv3 (see http://kv4p.com)

Replaces the Android Room database with a local SQLite file accessed through
plain JDBC (sqlite-jdbc provides the driver at runtime). The schema matches the
original app: a key/value `app_settings` table and a `channel_memories` table.

The DB lives at ~/.kv4p-desktop/kv4p.db so the app is self-contained per-user.
*/
package com.vagell.kv4pht.desktop.data;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class AppDatabase implements AutoCloseable {

  private final Connection conn;

  private AppDatabase(Connection conn) {
    this.conn = conn;
  }

  public static AppDatabase open() {
    try {
      File dir = new File(System.getProperty("user.home"), ".kv4p-desktop");
      if (!dir.exists() && !dir.mkdirs()) {
        throw new RuntimeException("Could not create data dir: " + dir);
      }
      Path dbFile = dir.toPath().resolve("kv4p.db");
      // sqlite-jdbc auto-registers via the JDBC ServiceLoader.
      Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
      AppDatabase db = new AppDatabase(c);
      db.createSchema();
      return db;
    } catch (SQLException e) {
      throw new RuntimeException("Failed to open database", e);
    }
  }

  private void createSchema() throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.executeUpdate(
          "CREATE TABLE IF NOT EXISTS app_settings ("
              + "  name TEXT NOT NULL PRIMARY KEY, value TEXT)");
      st.executeUpdate(
          "CREATE TABLE IF NOT EXISTS channel_memories ("
              + "  memoryId INTEGER PRIMARY KEY AUTOINCREMENT,"
              + "  name TEXT, frequency TEXT, offset INTEGER NOT NULL,"
              + "  tone TEXT, \"group\" TEXT)");
    }
  }

  // ---- Settings (key/value) ----
  public synchronized String getSetting(String name) {
    try (PreparedStatement ps =
        conn.prepareStatement("SELECT value FROM app_settings WHERE name = ?")) {
      ps.setString(1, name);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized String getSetting(String name, String defaultValue) {
    String v = getSetting(name);
    return v == null ? defaultValue : v;
  }

  public synchronized void setSetting(String name, String value) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO app_settings(name, value) VALUES(?, ?) "
                + "ON CONFLICT(name) DO UPDATE SET value = excluded.value")) {
      ps.setString(1, name);
      ps.setString(2, value);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // ---- Channel memories ----
  public synchronized List<ChannelMemory> getAllMemories() {
    List<ChannelMemory> list = new ArrayList<>();
    try (Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT memoryId, name, frequency, offset, tone, \"group\" "
                    + "FROM channel_memories ORDER BY frequency")) {
      while (rs.next()) {
        ChannelMemory m = new ChannelMemory();
        m.memoryId = rs.getInt("memoryId");
        m.name = rs.getString("name");
        m.frequency = rs.getString("frequency");
        m.offset = rs.getInt("offset");
        m.tone = rs.getString("tone");
        m.group = rs.getString("group");
        list.add(m);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    return list;
  }

  public synchronized void insertMemory(ChannelMemory m) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO channel_memories(name, frequency, offset, tone, \"group\") "
                + "VALUES(?,?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, m.name);
      ps.setString(2, m.frequency);
      ps.setInt(3, m.offset);
      ps.setString(4, m.tone);
      ps.setString(5, m.group);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        if (keys.next()) m.memoryId = keys.getInt(1);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized void updateMemory(ChannelMemory m) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "UPDATE channel_memories SET name=?, frequency=?, offset=?, tone=?, \"group\"=? "
                + "WHERE memoryId=?")) {
      ps.setString(1, m.name);
      ps.setString(2, m.frequency);
      ps.setInt(3, m.offset);
      ps.setString(4, m.tone);
      ps.setString(5, m.group);
      ps.setInt(6, m.memoryId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized void deleteMemory(int memoryId) {
    try (PreparedStatement ps =
        conn.prepareStatement("DELETE FROM channel_memories WHERE memoryId=?")) {
      ps.setInt(1, memoryId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void close() {
    try {
      conn.close();
    } catch (SQLException ignored) {
    }
  }
}
