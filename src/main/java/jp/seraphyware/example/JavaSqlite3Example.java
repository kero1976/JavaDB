package jp.seraphyware.example;

import java.util.stream.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class JavaSqlite3Example {

	public static void main(String[] args) throws Exception {
		String dbname = (args.length > 0) ? args[0] : "example.db";
		int mxThread = (args.length > 1) ? Integer.parseInt(args[1]) : 1;
		int mxLoop = (args.length > 2) ? Integer.parseInt(args[2]) : 100;

		initDb(dbname);

		ExecutorService execSrv = Executors.newFixedThreadPool(mxThread);

		CountDownLatch latch = new CountDownLatch(mxThread);
		IntStream.range(0, mxThread).mapToObj(threadNo -> (Runnable) () -> {
			try {
				latch.countDown();
				latch.await();
				run(dbname, threadNo, mxLoop);

			} catch (Exception ex) {
				System.err.println("failed tid:" + threadNo + ", " + ex.toString());
				ex.printStackTrace(System.err);
			}
		}).forEach(execSrv::execute);

		execSrv.shutdown();
		execSrv.awaitTermination(60, TimeUnit.SECONDS);

		showCounts(dbname);
		System.out.println("done.");
	}

	private static Connection createConnection(String dbname) throws SQLException {
		// sqlite-jdbc-v3.23.jarには、META-INF/services/java.sql.Driverがあるので以下は不要
		//Class.forName("org.sqlite.JDBC");

		// sqlite3のpragmaを指定する
		// https://www.sqlite.org/pragma.html
		// https://www.net-newbie.com/sqlite/lang.html
		// http://nave-kazu.hatenablog.com/entry/2015/12/18/140634
		Properties props = new Properties();
		props.put("journal_mode", "WAL"); // delete, truncate, wal (Write-Ahead Logging: ログ先行書き込み)
		props.put("sync_mode", "OFF"); // データベース書き込み完了を待たない。

		//SQLITE3のあれこれ、色々
		// http://d.hatena.ne.jp/maachang/20131231/1388466979
		props.put("busy_timeout", 10000); // 即時タイムアウトせず、しばらく待つ

		String url = "jdbc:sqlite:" + dbname;
		return DriverManager.getConnection(url, props);
	}

	private static void initDb(String dbname) throws SQLException {
		try (Connection conn = createConnection(dbname)) {
			try (Statement stm = conn.createStatement()) {
				// sqlite3ではprimarykeyが未設定でinsertするとユニークな適当な値を自動的にセットする
				// ("AUTOINCREMENT"を指定すると連番が振られる。暗黙のrowid割り当てよりも、やや遅くなるらしい)
				stm.execute("CREATE TABLE IF NOT EXISTS testtbl (" +
						"id INTEGER PRIMARY KEY, tid INTEGER, val VARCHAR," +
						"regdate TIMESTAMP default CURRENT_TIMESTAMP)"); // 日付型(実体は文字列)で現在UTC時刻
			}
		}
	}

	private static void run(String dbname, int threadNo, int mxLoop) throws SQLException {
		try (Connection conn = createConnection(dbname)) {
			conn.setAutoCommit(false);
			try (PreparedStatement stm = conn.prepareStatement("INSERT INTO testtbl(tid, val) VALUES(?, ?)")) {
				for (int idx = 0; idx < mxLoop; idx++) {
					stm.setInt(1, threadNo);
					stm.setString(2, String.format("%05d", idx));
					stm.addBatch();
				}
				int[] result = stm.executeBatch();
				long affectCount = Arrays.stream(result).filter(r -> r > 0).sum();
				System.out.println(String.format("result tid %03d, count=%5d", threadNo, affectCount));
			}
			conn.commit();
		}
	}

	private static void showCounts(String dbname) throws SQLException {
		try (Connection conn = createConnection(dbname)) {
			try (Statement stm = conn.createStatement()) {
				try (ResultSet rs = stm.executeQuery("SELECT tid, COUNT(*) cnt, MAX(regdate) lastregdate " +
						"FROM testtbl GROUP BY tid ORDER BY tid")) {
					while (rs.next()) {
						int tid = rs.getInt(1);
						int cnt = rs.getInt(2);
						String lastregdate = rs.getString(3);
						System.out.println(String.format("threadid %03d = %5d %s", tid, cnt, lastregdate));
					}
				}
			}
		}
	}
}