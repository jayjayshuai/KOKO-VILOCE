package cn.kokonexus.asset.infrastructure.storage;

import java.io.InputStream;
import java.nio.file.Path;

/** 视频独立的流式私有对象适配器，禁止复用图片的5MiB整块读取。 */
public interface VideoStorage {
    /** 固定服务端对象键，文件长度不超过已预留预算。 */ void put(String key, Path file);
    /** 只读取指定字节范围，调用方必须关闭流。 */ InputStream open(String key, long offset, long length);
    /** 删除确切对象的所有版本及删除标记，并核验不存在；未知结果必须抛错，截止时间来自同一单调时钟。 */
    void removeAndConfirm(String key, long deadlineNanos);
}
