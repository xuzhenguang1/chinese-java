package 中文.标准库;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件：java.nio.file.Files 的中文别名，统一使用 UTF-8。
 */
public final class 文件 {

    private 文件() {
    }

    public static String 读文本(Path 路径) throws IOException {
        return Files.readString(路径, StandardCharsets.UTF_8);
    }

    public static void 写文本(Path 路径, String 内容) throws IOException {
        Path 上级 = 路径.toAbsolutePath().getParent();
        if (上级 != null) {
            Files.createDirectories(上级);
        }
        Files.writeString(路径, 内容, StandardCharsets.UTF_8);
    }

    public static boolean 存在(Path 路径) {
        return Files.exists(路径);
    }

    public static void 建目录(Path 路径) throws IOException {
        Files.createDirectories(路径);
    }

    public static List<Path> 列举(Path 目录) throws IOException {
        try (var 流 = Files.list(目录)) {
            return 流.toList();
        }
    }

    public static long 大小(Path 路径) throws IOException {
        return Files.size(路径);
    }

    public static void 删除(Path 路径) throws IOException {
        Files.deleteIfExists(路径);
    }
}
