package com.swagger.ai.enhancer.ai.rag;

/**
 * 抛出此异常表示：当前向量数据库不支持此显式操作，
 * 对应 API 应返回 {"status": "not_applicable"} 供前端提示。
 */
public class NotApplicableForVectorStoreException extends RuntimeException {
    public NotApplicableForVectorStoreException(String message) {
        super(message);
    }

    public NotApplicableForVectorStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
