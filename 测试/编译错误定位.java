// 预期失败：javac 语义错误，用于验证报错位置能否回映射到中文源码
类 编译错误定位 {
    公有 静态 无 main(String[] 参数) {
        整数 数量 = "文字";
        System.out.println(数量);
    }
}
