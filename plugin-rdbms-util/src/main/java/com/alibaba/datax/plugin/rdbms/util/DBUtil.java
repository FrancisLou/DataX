package com.alibaba.datax.plugin.rdbms.util;

import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.util.RetryUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public final class DBUtil {
    private static final Logger LOG = LoggerFactory.getLogger(DBUtil.class);

    private DBUtil() {
    }

    public static String chooseJdbcUrl(final DataBaseType dataBaseType, final List<String> jdbcUrls,
                                       final String username, final String password,
                                       final List<String> preSql) {
        if (null == jdbcUrls || jdbcUrls.isEmpty()) {
            throw DataXException.asDataXException(DBUtilErrorCode.JDBC_CONTAINS_BLANK_ERROR, String.format("jdbcURL in [%s] 不能为空.",
                    StringUtils.join(jdbcUrls, ",")));
        }

        try {
            return RetryUtil.executeWithRetry(new Callable<String>() {

                @Override
                public String call() throws Exception {
                    boolean connOK = false;
                    for (String url : jdbcUrls) {
                        if (StringUtils.isNotBlank(url)) {
                            url = url.trim();
                            if (null != preSql && !preSql.isEmpty()) {
                                connOK = testConnWithoutRetry(dataBaseType, url,
                                        username, password, preSql);
                            } else {
                                connOK = testConnWithoutRetry(dataBaseType, url,
                                        username, password);
                            }
                            if (connOK) {
                                return url;
                            }
                        }
                    }
                    throw new Exception("No available jdbcURL yet.");
                }
            }, 3, 1000L, true);
        } catch (Exception e) {
            throw DataXException.asDataXException(DBUtilErrorCode.CONN_DB_ERROR, String.format("无法从:%s 中找到可连接的jdbcURL.",
                    StringUtils.join(jdbcUrls, ",")), e);
        }

    }


    /**
     * Get direct JDBC connection
     * <p/>
     * if connecting failed, try to connect for MAX_TRY_TIMES times
     * <p/>
     * NOTE: In DataX, we don't need connection pool in fact
     */
    public static Connection getConnection(final DataBaseType dataBaseType, final String jdbcUrl,
                                           final String username, final String password) {

        try {
            return RetryUtil.executeWithRetry(new Callable<Connection>() {
                @Override
                public Connection call() throws Exception {
                    return DBUtil.connect(dataBaseType, jdbcUrl, username, password);
                }
            }, Constant.MAX_TRY_TIMES, 1000L, true);
        } catch (Exception e) {
            throw DataXException.asDataXException(DBUtilErrorCode.CONN_DB_ERROR,
                    String.format("获取数据库连接失败. 连接信息是:%s .",
                            jdbcUrl), e);
        }

    }

    private static synchronized Connection connect(DataBaseType dataBaseType, String url, String user,
                                                   String pass) throws Exception {
        Class.forName(dataBaseType.getDriverClassName());
        DriverManager.setLoginTimeout(Constant.TIMEOUT_SECONDS);
        return DriverManager.getConnection(url, user, pass);
    }

    /**
     * a wrapped method to execute select-like sql statement .
     *
     * @param conn Database connection .
     * @param sql  sql statement to be executed
     * @return a {@link ResultSet}
     * @throws SQLException if occurs SQLException.
     */
    public static ResultSet query(Connection conn, String sql, int fetchSize)
            throws SQLException {
        Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY);
        stmt.setFetchSize(fetchSize);
        return query(stmt, sql);
    }

    /**
     * a wrapped method to execute select-like sql statement .
     *
     * @param stmt {@link Statement}
     * @param sql  sql statement to be executed
     * @return a {@link ResultSet}
     * @throws SQLException if occurs SQLException.
     */
    public static ResultSet query(Statement stmt, String sql)
            throws SQLException {
        return stmt.executeQuery(sql);
    }

    public static void executeSqlWithoutResultSet(Statement stmt, String sql)
            throws SQLException {
        stmt.execute(sql);
    }

    /**
     * Close {@link ResultSet}, {@link Statement} referenced by this
     * {@link ResultSet}
     *
     * @param rs {@link ResultSet} to be closed
     * @throws IllegalArgumentException
     */
    public static void closeResultSet(ResultSet rs) {
        try {
            if (null != rs) {
                Statement stmt = rs.getStatement();
                if (null != stmt) {
                    stmt.close();
                    stmt = null;
                }
                rs.close();
            }
            rs = null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void closeDBResources(ResultSet rs, Statement stmt,
                                        Connection conn) {
        if (null != rs) {
            try {
                rs.close();
            } catch (SQLException unused) {
            }
        }

        if (null != stmt) {
            try {
                stmt.close();
            } catch (SQLException unused) {
            }
        }

        if (null != conn) {
            try {
                conn.close();
            } catch (SQLException unused) {
            }
        }
    }

    public static void closeDBResources(Statement stmt, Connection conn) {
        closeDBResources(null, stmt, conn);
    }

    public static List<String> getTableColumns(DataBaseType dataBaseType, String jdbcUrl,
                                               String user, String pass, String tableName) {
        List<String> columns = new ArrayList<String>();
        Connection conn = getConnection(dataBaseType, jdbcUrl, user, pass);
        try {
            Statement statement = conn.createStatement();
            String queryColumnSql = String.format("select * from %s where 1=2", tableName);
            ResultSet rs = statement.executeQuery(queryColumnSql);
            ResultSetMetaData rsMetaData = rs.getMetaData();
            for (int i = 0, len = rsMetaData.getColumnCount(); i < len; i++) {
                columns.add(rsMetaData.getColumnName(i + 1));
            }

        } catch (SQLException e) {
            throw DataXException.asDataXException(DBUtilErrorCode.CONN_DB_ERROR, e);
        }
        return columns;

    }

    public static boolean testConnWithoutRetry(DataBaseType dataBaseType, String url, String user, String pass) {
        try {
            Connection connection = connect(dataBaseType, url, user, pass);
            if (null != connection) {
                return true;
            }
        } catch (Exception e) {
            LOG.warn("正在测试 jdbcUrl 连通性, 目前 jdbcUrl:{} 不可连, 原因是:{} .", url,
                    e.getMessage());
        }

        return false;
    }

    public static boolean testConnWithoutRetry(DataBaseType dataBaseType, String url, String user,
                                               String pass, List<String> preSql) {
        try {
            Connection connection = connect(dataBaseType, url, user, pass);
            if (null != connection) {
                for (String pre : preSql) {
                    if (doPreCheck(connection, pre) == false) {
                        LOG.warn("doPreCheck failed.");
                        return false;
                    }
                }
                return true;
            }
        } catch (Exception e) {
            LOG.warn("正在测试 jdbcUrl 连通性, 目前 jdbcUrl:{} 不可连, 原因是:{} .", url,
                    e.getMessage());
        }

        return false;
    }

    public static ResultSet query(Connection conn, String sql)
            throws SQLException {
        Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY);
        return query(stmt, sql);
    }

    private static boolean doPreCheck(Connection conn, String pre) {
        try {
            ResultSet rs = query(conn, pre);

            int checkResult = -1;
            if (rs.next()) {
                checkResult = rs.getInt(1);
                if (rs.next()) {
                    LOG.warn("读取数据库表前的检查语句:{} 未通过. 根据 DataX 规定，preCheck 只有返回一条数据且返回值为0 才会通过.", pre);
                    return false;
                }

            }

            if (0 == checkResult) {
                return true;
            }

            LOG.warn("读取数据库表前的检查语句:{} 未通过. 根据 DataX 规定，preCheck 只有返回一条数据且返回值为0 才会通过.", pre);
        } catch (Exception e) {
            LOG.warn("读取数据库表前的检查语句:{} 执行时发生异常:{}. 根据 DataX 规定，preCheck 只有返回一条数据且返回值为0 才会通过.", pre, e.getMessage());
        }
        return false;
    }

}
