package dev.lovelace.loveshops.database;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Tiny pool of long-lived JDBC connections. The old code opened a brand-new SQLite connection
 * (file open, WAL/pragma setup) for every operation, which is what stalled the main thread.
 * Never blocks: when no idle connection exists a new one is opened, and on close it is kept
 * only while fewer than {@code maxIdle} are idle (extra ones are really closed). Callers keep
 * using try-with-resources unchanged.
 * <p>
 * Every statement created on a borrowed connection is closed when the connection goes back to the pool.
 * An unclosed ResultSet keeps a read snapshot open in WAL mode, and the next writer on that pooled
 * connection fails with SQLITE_BUSY_SNAPSHOT (busy_timeout does not help), so a leak in one caller
 * must not poison the connection for the next one.
 */
public final class ConnectionPool {

    public interface Factory {
        Connection open() throws SQLException;
    }

    private final Factory factory;
    private final BlockingQueue<Connection> idle;
    private volatile boolean closed;

    public ConnectionPool(Factory factory, int maxIdle) {
        this.factory = factory;
        this.idle = new ArrayBlockingQueue<>(Math.max(1, maxIdle));
    }

    public Connection borrow() throws SQLException {
        if (closed) throw new SQLException("Connection pool is closed");
        Connection raw;
        while ((raw = idle.poll()) != null) {
            if (!raw.isClosed() && raw.isValid(1)) return wrap(raw);
            closeQuietly(raw);
        }
        return wrap(factory.open());
    }

    public int idleCount() {
        return idle.size();
    }

    public void close() {
        closed = true;
        Connection c;
        while ((c = idle.poll()) != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
            }
        }
    }

    private Connection wrap(Connection raw) {
        boolean[] released = {false};
        List<Statement> statements = new ArrayList<>();
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("close") && method.getParameterCount() == 0) {
                synchronized (released) {
                    if (released[0]) return null;
                    released[0] = true;
                }
                closeStatements(statements);
                release(raw);
                return null;
            }
            if (name.equals("isClosed") && method.getParameterCount() == 0) {
                synchronized (released) {
                    if (released[0]) return true;
                }
            }
            try {
                Object result = method.invoke(raw, args);
                if (result instanceof Statement statement) {
                    synchronized (statements) {
                        statements.add(statement);
                    }
                }
                return result;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
    }

    private static void closeStatements(List<Statement> statements) {
        List<Statement> copy;
        synchronized (statements) {
            copy = new ArrayList<>(statements);
            statements.clear();
        }
        for (Statement statement : copy) {
            try {
                statement.close();
            } catch (SQLException ignored) {
            }
        }
    }

    private static void closeQuietly(Connection raw) {
        try {
            raw.close();
        } catch (SQLException ignored) {
        }
    }

    private void release(Connection raw) {
        try {
            if (raw.isClosed()) return;
            if (!raw.getAutoCommit()) {
                raw.rollback();
                raw.setAutoCommit(true);
            }
            if (closed || !idle.offer(raw)) {
                raw.close();
            }
        } catch (SQLException e) {
            try {
                raw.close();
            } catch (SQLException ignored) {
            }
        }
    }
}
