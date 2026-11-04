// 预期失败：顿号与句号无法映射到半角，应报 CJK002
类 不支持标点 {
    公有 静态 无 main(String[] 参数) {
        整数 甲 = 1、2。
        System.out.println(甲)
    }
}
