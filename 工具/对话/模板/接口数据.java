// 模板 · 接口数据：发起 HTTP 请求、取状态码、输出正文。
导入 中文.标准库.*;

类 接口数据 {
    入口 {
        字符串 网址 = "https://httpbin.org/get";
        try {
            整数 状态码 = 网络.取状态码(网址);
            字符串 正文 = 网络.取文本(网址);
            println("状态码：{状态码}");
            println("响应长度：{正文.length()} 字符");
            println(正文);
        } catch (Exception 出错) {
            println("请求失败：" + 出错.getMessage());
        }
    }
}
