import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class Schema {
    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:runs.db");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT name, sql FROM sqlite_master WHERE type='table'")) {
            while (rs.next()) {
                System.out.println("== " + rs.getString(1));
                System.out.println(rs.getString(2));
            }
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:runs.db");
             Statement s2 = c.createStatement();
             ResultSet r2 = s2.executeQuery("SELECT (SELECT COUNT(*) FROM events), (SELECT COUNT(*) FROM runs), (SELECT COUNT(*) FROM evaluations), (SELECT COUNT(*) FROM diagnoses)")) {
            r2.next();
            System.out.println("counts: events=" + r2.getLong(1) + " runs=" + r2.getLong(2)
                    + " evaluations=" + r2.getLong(3) + " diagnoses=" + r2.getLong(4));
        }
    }
}
