package 中文.原生.中间;

/** 解释器运行期异常：消息一律中文，由 CLI 输出到 stderr。 */
public final class 解释器异常 extends RuntimeException {

    public 解释器异常(String 消息) {
        super(消息);
    }
}
