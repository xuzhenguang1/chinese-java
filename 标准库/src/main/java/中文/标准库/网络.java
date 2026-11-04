package 中文.标准库;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 网络：基于 java.net.http.HttpClient 的 HTTP GET 中文别名。
 * 网络请求可能抛出 IOException（连接失败）或 InterruptedException（线程被中断）。
 */
public final class 网络 {

    private static final HttpClient 客户端 = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private 网络() {
    }

    /** GET 请求，返回响应正文（按 UTF-8 解码）。 */
    public static String 取文本(String 网址) throws IOException, InterruptedException {
        return 取(网址).body();
    }

    /** GET 请求，返回 HTTP 状态码。 */
    public static int 取状态码(String 网址) throws IOException, InterruptedException {
        return 取(网址).statusCode();
    }

    /** GET 请求，返回完整响应（状态码、响应头、正文）。 */
    public static HttpResponse<String> 取(String 网址) throws IOException, InterruptedException {
        HttpRequest 请求 = HttpRequest.newBuilder(URI.create(网址))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return 客户端.send(请求, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** GET 请求并把正文以 UTF-8 写入目标文件（自动创建上级目录）。 */
    public static void 下载到(String 网址, Path 目标) throws IOException, InterruptedException {
        文件.写文本(目标, 取文本(网址));
    }
}
