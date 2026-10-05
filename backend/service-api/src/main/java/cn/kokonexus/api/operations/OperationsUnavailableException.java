package cn.kokonexus.api.operations;

/** 运营能力未配置或依赖不可用，须 fail-closed，网关返回 503。 */
public class OperationsUnavailableException extends RuntimeException {

    public OperationsUnavailableException(String message) {
        super(message);
    }
}
