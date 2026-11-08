package 中文.标准库;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 中文标准库别名的单元测试。类名以「测试」结尾，由 surefire 的 include 规则匹配。
 */
class 标准库测试 {

    @TempDir
    Path 临时目录;

    @Test
    void 列表增删查与排序() {
        List<String> 名字 = new ArrayList<>(List.of("小明", "小红", "小刚"));
        assertEquals(3, 名字.size());
        assertEquals("小明", 名字.get(0));
        assertEquals("小刚", 名字.get(2));

        名字.add("小美");
        名字.remove(1);
        assertEquals(3, 名字.size());
        assertTrue(名字.contains("小美"));
        assertFalse(名字.contains("小红"));

        Collections.sort(名字);
        assertEquals("小刚", 名字.get(0));
        assertTrue(List.of().isEmpty());
    }

    @Test
    void 映射存取与默认值() {
        Map<String, Integer> 库存 = new LinkedHashMap<>();
        库存.put("苹果", 3);
        库存.put("香蕉", 5);

        assertEquals(3, 库存.get("苹果"));
        assertEquals(0, 库存.getOrDefault("葡萄", 0));
        assertTrue(库存.containsKey("香蕉"));
        assertTrue(库存.containsValue(5));
        assertEquals(2, 库存.size());

        库存.remove("苹果");
        assertEquals(1, 库存.size());
        库存.clear();
        assertTrue(库存.isEmpty());
    }

    @Test
    void 文本判空与连接() {
        assertTrue(文本.是否为空(null));
        assertTrue(文本.是否空白("   "));
        assertFalse(文本.是否空白("你好"));
        assertEquals("你好", 文本.去空白(" 你好 "));
        assertEquals("甲/乙/丙", 文本.连接("/", "甲", "乙", "丙"));
        assertEquals("哈哈哈", 文本.重复("哈", 3));
    }

    @Test
    void 数值转换() {
        assertEquals("42", 数值.转文本(42));
        assertTrue(数值.是整数("42"));
        assertFalse(数值.是整数("4.2"));
    }

    @Test
    void 文件读写往返() throws IOException {
        Path 目标 = 临时目录.resolve("说明.txt");
        文件.写文本(目标, "第一行\n第二行\n");

        assertTrue(文件.存在(目标));
        assertEquals("第一行\n第二行\n", 文件.读文本(目标));
        assertTrue(文件.大小(目标) > 0);
        assertEquals(1, 文件.列举(临时目录).size());

        文件.删除(目标);
        assertFalse(文件.存在(目标));
    }

    @Test
    void 时间格式化与相差() {
        LocalDateTime 时刻 = LocalDateTime.of(2026, 10, 30, 9, 30);
        assertEquals("2026-10-30 09:30", 时间.格式化(时刻, "yyyy-MM-dd HH:mm"));

        LocalDate 今天 = LocalDate.of(2026, 10, 30);
        LocalDate 后天 = 时间.加天数(今天, 2);
        assertEquals(2, 时间.相差天数(今天, 后天));
        assertTrue(时间.时间戳() > 0);
    }

    @Test
    void 集合工具填充() {
        List<Integer> 数值 = new ArrayList<>(List.of(5, 2, 9));
        集合工具.填充(数值, 0);
        assertEquals(List.of(0, 0, 0), 数值);
    }

    @Test
    void 并发提交与取结果() throws Exception {
        ExecutorService 线程池 = 并发.固定线程池(4);
        try {
            List<Future<Long>> 结果 = new ArrayList<>();
            for (int 序号 = 1; 序号 <= 100; 序号++) {
                long 值 = 序号;
                结果.add(并发.提交(线程池, () -> 值));
            }
            long 总和 = 0;
            for (Future<Long> 单个 : 结果) {
                总和 += 并发.取结果(单个);
            }
            assertEquals(5050, 总和);
            assertTrue(并发.当前线程名().length() > 0);
        } finally {
            并发.关闭(线程池);
        }
    }

    @Test
    void 网络请求本地服务() throws Exception {
        HttpServer 服务 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] 正文 = "你好，网络".getBytes(StandardCharsets.UTF_8);
        服务.createContext("/hello", 交换 -> {
            交换.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            交换.sendResponseHeaders(200, 正文.length);
            try (var 输出流 = 交换.getResponseBody()) {
                输出流.write(正文);
            }
        });
        服务.start();
        try {
            String 网址 = "http://127.0.0.1:" + 服务.getAddress().getPort() + "/hello";
            assertEquals("你好，网络", 网络.取文本(网址));
            assertEquals(200, 网络.取状态码(网址));

            Path 目标 = 临时目录.resolve("下载.txt");
            网络.下载到(网址, 目标);
            assertEquals("你好，网络", 文件.读文本(目标));
        } finally {
            服务.stop(0);
        }
    }

    @Test
    void 日志记录到处理器() {
        List<String> 记录 = new ArrayList<>();
        Logger 日志器 = 日志.取("cjc.测试");
        日志器.setUseParentHandlers(false);
        日志器.setLevel(Level.ALL);
        Handler 处理器 = new Handler() {
            @Override
            public void publish(LogRecord 条目) {
                记录.add(条目.getLevel() + ":" + 条目.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        日志器.addHandler(处理器);
        try {
            日志.信息(日志器, "你好");
            日志.警告(日志器, "注意");
            日志.调试(日志器, "细节");
            assertEquals(List.of("INFO:你好", "WARNING:注意", "FINE:细节"), 记录);
        } finally {
            日志器.removeHandler(处理器);
        }
    }

    @Test
    void 数据JSON编码() {
        Map<String, Object> 配置 = new LinkedHashMap<>();
        配置.put("名字", "小明");
        配置.put("年龄", 18);
        配置.put("标签", List.of("甲", "乙"));
        配置.put("启用", true);
        配置.put("备注", null);
        assertEquals(
                "{\"名字\":\"小明\",\"年龄\":18,\"标签\":[\"甲\",\"乙\"],\"启用\":true,\"备注\":null}",
                数据.转JSON(配置));
        assertEquals("\"引号\\\"与换行\\n\"", 数据.转JSON("引号\"与换行\n"));

        String 美化 = 数据.转JSON(配置, true);
        assertTrue(美化.contains("\n"));
        assertTrue(美化.contains("  \"名字\": \"小明\""));
    }

    @Test
    void 输出多实参依次拼接() {
        PrintStream 原输出 = System.out;
        ByteArrayOutputStream 缓冲 = new ByteArrayOutputStream();
        System.setOut(new PrintStream(缓冲, true, StandardCharsets.UTF_8));
        try {
            输出.打印("姓名：", "小明", "，年龄：", 18);
            输出.打印行(" / ", "甲", "乙");
            输出.打印行();
        } finally {
            System.setOut(原输出);
        }
        String 换行 = System.lineSeparator();
        assertEquals("姓名：小明，年龄：18 / 甲乙" + 换行 + 换行, 缓冲.toString(StandardCharsets.UTF_8));
    }

    @Test
    void 输出错误多实参依次拼接() {
        PrintStream 原错误 = System.err;
        ByteArrayOutputStream 缓冲 = new ByteArrayOutputStream();
        System.setErr(new PrintStream(缓冲, true, StandardCharsets.UTF_8));
        try {
            输出.错误("错误：", "文件不存在");
            输出.错误();
        } finally {
            System.setErr(原错误);
        }
        String 换行 = System.lineSeparator();
        assertEquals("错误：文件不存在" + 换行 + 换行, 缓冲.toString(StandardCharsets.UTF_8));
    }
}
