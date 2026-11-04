package 中文.标准库;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 并发：java.util.concurrent 常用操作的中文别名。
 * 只转发 JDK 能力，线程模型与异常语义保持不变。
 */
public final class 并发 {

    private 并发() {
    }

    /** Executors.newFixedThreadPool */
    public static ExecutorService 固定线程池(int 线程数) {
        return Executors.newFixedThreadPool(线程数);
    }

    /** Executors.newSingleThreadExecutor */
    public static ExecutorService 单线程池() {
        return Executors.newSingleThreadExecutor();
    }

    /** ExecutorService.execute */
    public static void 执行(ExecutorService 线程池, Runnable 任务) {
        线程池.execute(任务);
    }

    /** ExecutorService.submit(Callable) */
    public static <T> Future<T> 提交(ExecutorService 线程池, Callable<T> 任务) {
        return 线程池.submit(任务);
    }

    /** Future.get() */
    public static <T> T 取结果(Future<T> 结果) throws InterruptedException, ExecutionException {
        return 结果.get();
    }

    /** 关闭线程池；60 秒内未结束则强制关闭。 */
    public static void 关闭(ExecutorService 线程池) throws InterruptedException {
        线程池.shutdown();
        if (!线程池.awaitTermination(60, TimeUnit.SECONDS)) {
            线程池.shutdownNow();
        }
    }

    /** Thread.sleep */
    public static void 休眠(long 毫秒) throws InterruptedException {
        Thread.sleep(毫秒);
    }

    /** Thread.currentThread().getName() */
    public static String 当前线程名() {
        return Thread.currentThread().getName();
    }
}
