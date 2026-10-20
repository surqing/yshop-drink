package co.yixiang.yshop.module.store.printer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Execute the production mysql-client DELIMITER script without changing its statements. */
final class PrinterMigrationSupport {
    static JdbcTemplate mysql() throws Exception {
        Properties p=new Properties();
        String config=System.getenv("PRINTER_ACCEPTANCE_CONFIG");
        if(config==null) throw new IllegalStateException("OWNED_MYSQL_CONFIG_REQUIRED");
        try(var in=Files.newInputStream(Path.of(config))) {p.load(in);}
        String url=p.getProperty("url"),user=p.getProperty("username");
        if(!url.matches("jdbc:mysql://127[.]0[.]0[.]1:[1-9][0-9]{0,4}/yshop_quality_printer_[a-f0-9]{16}[?].*")
            ||!user.matches("qa_printer_[a-f0-9]{16}")) throw new IllegalStateException("OWNED_DATABASE_REQUIRED");
        return new JdbcTemplate(new DriverManagerDataSource(url,user,p.getProperty("password")));
    }
    static void migrate(JdbcTemplate jdbc,boolean mysql) throws Exception {
        String sql=Files.readString(Path.of("../../sql/migrations/2026-10-05-printer-management.sql"));
        // H2 cannot model MySQL information_schema / enforced checks. Only MySQL acceptance
        // exercises this production preflight; H2 is explicitly service/transaction coverage.
        if(!mysql) sql=sql.replaceAll("(?s)-- MYSQL_PREFLIGHT_BEGIN.*?-- MYSQL_PREFLIGHT_END", "")
            .replaceAll(" CHARACTER SET ascii COLLATE ascii_bin", "")
            .replaceAll(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4(?: COLLATE=utf8mb4_0900_ai_ci)?", "")
            .replaceAll("b'([01])'", "$1");
        String delimiter=";"; StringBuilder statement=new StringBuilder();
        for(String line:sql.split("\\R")) {
            String trimmed=line.trim();
            if(trimmed.startsWith("--")||trimmed.isEmpty()) continue;
            if(trimmed.startsWith("DELIMITER ")) { delimiter=trimmed.substring(10).trim();continue; }
            statement.append(line).append('\n');
            String pending=statement.toString().trim();
            if(pending.endsWith(delimiter)) {jdbc.execute(pending.substring(0,pending.length()-delimiter.length()));statement.setLength(0);}
        }
        if(!statement.toString().isBlank()) throw new IllegalStateException("UNTERMINATED_MIGRATION");
    }
    static void upstream(JdbcTemplate jdbc) throws Exception {
        for(String table:List.of("yshop_printer_binding","yshop_printer_test_job","yshop_printer_provider_token","yshop_printer","system_menu","yshop_store_shop")) jdbc.execute("DROP TABLE IF EXISTS "+table);
        String source=Files.readString(Path.of("../../sql/yixiang-drink-open.sql"));
        for(String table:List.of("system_menu","yshop_store_shop")) {
            Matcher m=Pattern.compile("(?ms)^CREATE TABLE `"+table+"` \\(.*?;").matcher(source);
            if(!m.find()) throw new IllegalStateException("UPSTREAM_TABLE_MISSING"); jdbc.execute(m.group());
        }
        Matcher menus=Pattern.compile("(?m)^INSERT INTO `system_menu` VALUES \\((?:2000|230[8-9]|231[0-3]),.*?;$").matcher(source);
        while(menus.find()) jdbc.execute(menus.group());
        jdbc.update("INSERT INTO yshop_store_shop (id,name,mobile,images,address,address_map,start_time,end_time,lng,lat) VALUES (1,'synthetic-shop-one','','','','',NOW(),NOW(),'0','0'),(2,'synthetic-shop-two','','','','',NOW(),NOW(),'0','0')");
    }
    private PrinterMigrationSupport() {}
}
