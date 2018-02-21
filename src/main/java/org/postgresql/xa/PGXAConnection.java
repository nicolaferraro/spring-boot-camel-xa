//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package org.postgresql.xa;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedList;
import javax.sql.XAConnection;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Logger;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.TransactionState;
import org.postgresql.ds.PGPooledConnection;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

public class PGXAConnection extends PGPooledConnection implements XAConnection, XAResource {
    private final BaseConnection conn;
    private final Logger logger;
    private Xid currentXid;
    private int state;
    private static final int STATE_IDLE = 0;
    private static final int STATE_ACTIVE = 1;
    private static final int STATE_ENDED = 2;
    private boolean localAutoCommitMode = true;

    private void debug(String s) {
        this.logger.debug("XAResource " + Integer.toHexString(this.hashCode()) + ": " + s);
    }

    public PGXAConnection(BaseConnection conn) throws SQLException {
        super(conn, true, true);
        this.conn = conn;
        this.state = 0;
        this.logger = conn.getLogger();
    }

    public Connection getConnection() throws SQLException {
        if (this.logger.logDebug()) {
            this.debug("PGXAConnection.getConnection called");
        }

        Connection conn = super.getConnection();
        if (this.state == 0) {
            conn.setAutoCommit(true);
        }

        PGXAConnection.ConnectionHandler handler = new PGXAConnection.ConnectionHandler(conn);
        return (Connection)Proxy.newProxyInstance(this.getClass().getClassLoader(), new Class[]{Connection.class, PGConnection.class}, handler);
    }

    public XAResource getXAResource() {
        return this;
    }

    public void start(Xid xid, int flags) throws XAException {
        if (this.logger.logDebug()) {
            this.debug("starting transaction xid = " + xid);
        }

        if (flags != 0 && flags != 134217728 && flags != 2097152) {
            throw new PGXAException(GT.tr("Invalid flags", new Object[0]), -5);
        } else if (xid == null) {
            throw new PGXAException(GT.tr("xid must not be null", new Object[0]), -5);
        } else if (this.state == 1) {
            throw new PGXAException(GT.tr("Connection is busy with another transaction", new Object[0]), -6);
        } else if (flags == 134217728) {
            throw new PGXAException(GT.tr("suspend/resume not implemented", new Object[0]), -3);
        } else {
            if (flags == 2097152) {
                if (this.state != 2) {
                    throw new PGXAException(GT.tr("Transaction interleaving not implemented", new Object[0]), -3);
                }

                if (!xid.equals(this.currentXid)) {
                    throw new PGXAException(GT.tr("Transaction interleaving not implemented", new Object[0]), -3);
                }
            } else if (this.state == 2) {
                throw new PGXAException(GT.tr("Transaction interleaving not implemented", new Object[0]), -3);
            }

            if (flags == 0) {
                try {
                    this.localAutoCommitMode = this.conn.getAutoCommit();
                    this.conn.setAutoCommit(false);
                } catch (SQLException var4) {
                    throw new PGXAException(GT.tr("Error disabling autocommit", new Object[0]), var4, -3);
                }
            }

            this.state = 1;
            this.currentXid = xid;
        }
    }

    public void end(Xid xid, int flags) throws XAException {
        if (this.logger.logDebug()) {
            this.debug("ending transaction xid = " + xid);
        }

        if (flags != 33554432 && flags != 536870912 && flags != 67108864) {
            throw new PGXAException(GT.tr("Invalid flags", new Object[0]), -5);
        } else if (xid == null) {
            throw new PGXAException(GT.tr("xid must not be null", new Object[0]), -5);
        } else if (this.state == 1 && this.currentXid.equals(xid)) {
            if (flags == 33554432) {
                throw new PGXAException(GT.tr("suspend/resume not implemented", new Object[0]), -3);
            } else {
                this.state = 2;
            }
        } else {
            throw new PGXAException(GT.tr("tried to call end without corresponding start call", new Object[0]), -6);
        }
    }

    public int prepare(Xid xid) throws XAException {
        if (this.logger.logDebug()) {
            this.debug("preparing transaction xid = " + xid);
        }

        if (!this.currentXid.equals(xid)) {
            throw new PGXAException(GT.tr("Not implemented: Prepare must be issued using the same connection that started the transaction", new Object[0]), -3);
        } else if (this.state != 2) {
            throw new PGXAException(GT.tr("Prepare called before end", new Object[0]), -5);
        } else {
            this.state = 0;
            this.currentXid = null;
            if (!this.conn.haveMinimumServerVersion(ServerVersion.v8_1)) {
                throw new PGXAException(GT.tr("Server versions prior to 8.1 do not support two-phase commit.", new Object[0]), -3);
            } else {
                try {
                    String s = RecoveredXid.xidToString(xid);
                    Statement stmt = this.conn.createStatement();

                    try {
                        stmt.executeUpdate("PREPARE TRANSACTION '" + s + "'");
                    } finally {
                        stmt.close();
                    }

                    this.conn.setAutoCommit(this.localAutoCommitMode);
                    return 0;
                } catch (SQLException var8) {
                    throw new PGXAException(GT.tr("Error preparing transaction", new Object[0]), var8, -3);
                }
            }
        }
    }

    public Xid[] recover(int flag) throws XAException {
        if (flag != 16777216 && flag != 8388608 && flag != 0 && flag != 25165824) {
            throw new PGXAException(GT.tr("Invalid flag", new Object[0]), -5);
        } else if ((flag & 16777216) == 0) {
            return new Xid[0];
        } else {
            try {
                Statement stmt = this.conn.createStatement();

                try {
                    ResultSet rs = stmt.executeQuery("SELECT gid FROM pg_prepared_xacts where database = current_database()");
                    LinkedList l = new LinkedList();

                    while(rs.next()) {
                        Xid recoveredXid = RecoveredXid.stringToXid(rs.getString(1));
                        if (recoveredXid != null) {
                            l.add(recoveredXid);
                        }
                    }

                    rs.close();
                    Xid[] var11 = (Xid[])l.toArray(new Xid[l.size()]);
                    return var11;
                } finally {
                    stmt.close();
                }
            } catch (SQLException var10) {
                throw new PGXAException(GT.tr("Error during recover", new Object[0]), var10, -3);
            }
        }
    }

    public void rollback(Xid xid) throws XAException {
        if (this.logger.logDebug()) {
            this.debug("rolling back xid = " + xid);
        }

        try {
            if (this.currentXid != null && xid.equals(this.currentXid)) {
                this.state = 0;
                this.currentXid = null;
                this.conn.rollback();
                this.conn.setAutoCommit(this.localAutoCommitMode);
            } else {
                String s = RecoveredXid.xidToString(xid);
                this.conn.setAutoCommit(true);
                Statement stmt = this.conn.createStatement();

                try {
                    stmt.executeUpdate("ROLLBACK PREPARED '" + s + "'");
                } finally {
                    stmt.close();
                }
            }

        } catch (SQLException var8) {
            if (PSQLState.UNDEFINED_OBJECT.getState().equals(var8.getSQLState())) {
                throw new PGXAException(GT.tr("Error rolling back prepared transaction", new Object[0]), var8, -4);
            } else {
                throw new PGXAException(GT.tr("Error rolling back prepared transaction", new Object[0]), var8, -3);
            }
        }
    }

    public void commit(Xid xid, boolean onePhase) throws XAException {
//        Runtime.getRuntime().halt(-1);
        if (this.logger.logDebug()) {
            this.debug("committing xid = " + xid + (onePhase ? " (one phase) " : " (two phase)"));
        }

        if (xid == null) {
            throw new PGXAException(GT.tr("xid must not be null", new Object[0]), -5);
        } else {
            if (onePhase) {
                this.commitOnePhase(xid);
            } else {
                this.commitPrepared(xid);
            }

        }
    }

    private void commitOnePhase(Xid xid) throws XAException {
        try {
            if (this.currentXid != null && this.currentXid.equals(xid)) {
                if (this.state != 2) {
                    throw new PGXAException(GT.tr("commit called before end", new Object[0]), -6);
                } else {
                    this.state = 0;
                    this.currentXid = null;
                    this.conn.commit();
                    this.conn.setAutoCommit(this.localAutoCommitMode);
                }
            } else {
                throw new PGXAException(GT.tr("Not implemented: one-phase commit must be issued using the same connection that was used to start it", new Object[0]), -3);
            }
        } catch (SQLException var3) {
            throw new PGXAException(GT.tr("Error during one-phase commit", new Object[0]), var3, -3);
        }
    }

    private void commitPrepared(Xid xid) throws XAException {
        try {
            if (this.state == 0 && this.conn.getTransactionState() == TransactionState.IDLE) {
                String s = RecoveredXid.xidToString(xid);
                this.localAutoCommitMode = this.conn.getAutoCommit();
                this.conn.setAutoCommit(true);
                Statement stmt = this.conn.createStatement();

                try {
                    stmt.executeUpdate("COMMIT PREPARED '" + s + "'");
                } finally {
                    stmt.close();
                    this.conn.setAutoCommit(this.localAutoCommitMode);
                }

            } else {
                throw new PGXAException(GT.tr("Not implemented: 2nd phase commit must be issued using an idle connection", new Object[0]), -3);
            }
        } catch (SQLException var8) {
            throw new PGXAException(GT.tr("Error committing prepared transaction", new Object[0]), var8, -3);
        }
    }

    public boolean isSameRM(XAResource xares) throws XAException {
        return xares == this;
    }

    public void forget(Xid xid) throws XAException {
        throw new PGXAException(GT.tr("Heuristic commit/rollback not supported", new Object[0]), -4);
    }

    public int getTransactionTimeout() {
        return 0;
    }

    public boolean setTransactionTimeout(int seconds) {
        return false;
    }

    private class ConnectionHandler implements InvocationHandler {
        private Connection con;

        public ConnectionHandler(Connection con) {
            this.con = con;
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (PGXAConnection.this.state != 0) {
                String methodName = method.getName();
                if (methodName.equals("commit") || methodName.equals("rollback") || methodName.equals("setSavePoint") || methodName.equals("setAutoCommit") && (Boolean)args[0]) {
                    throw new PSQLException(GT.tr("Transaction control methods setAutoCommit(true), commit, rollback and setSavePoint not allowed while an XA transaction is active.", new Object[0]), PSQLState.OBJECT_NOT_IN_STATE);
                }
            }

            try {
                if (method.getName().equals("equals")) {
                    Object arg = args[0];
                    if (Proxy.isProxyClass(arg.getClass())) {
                        InvocationHandler h = Proxy.getInvocationHandler(arg);
                        if (h instanceof PGXAConnection.ConnectionHandler) {
                            args = new Object[]{((PGXAConnection.ConnectionHandler)h).con};
                        }
                    }
                }

                return method.invoke(this.con, args);
            } catch (InvocationTargetException var6) {
                throw var6.getTargetException();
            }
        }
    }
}
