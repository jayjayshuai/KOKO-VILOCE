package cn.kokonexus.api.operations;

/** 运营资源未找到；与无权限及状态竞争分别返回 404/403/409。 */
public class OperationsNotFoundException extends RuntimeException {

    public OperationsNotFoundException(String message) {
        super(message);
    }
}
