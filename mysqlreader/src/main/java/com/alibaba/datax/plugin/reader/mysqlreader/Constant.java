package com.alibaba.datax.plugin.reader.mysqlreader;

/**
 * 用于插件解析用户配置时，需要进行标识（MARK）的常量的声明.
 * 
 */
public final class Constant {
	public static final int DEFAULT_FETCH_SIZE = 32;

	public static String CONN_MARK = "connection";

	public static String TABLE_NUMBER_MARK = "tableNumber";

	public static String TABLE_MODE = "tableMode";

	public static String QUERY_SQL_TEMPLATE_WHITOUT_WHERE = "select %s from %s ";

	public static String QUERY_SQL_TEMPLATE = "select %s from %s where (%s)";

}
