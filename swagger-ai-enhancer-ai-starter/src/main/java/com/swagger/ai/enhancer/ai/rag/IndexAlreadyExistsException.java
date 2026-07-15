package com.swagger.ai.enhancer.ai.rag;

/**
 * 索引已存在异常：向量库 createIndex 时若索引已存在则抛出。
 * 由各向量库子模块在检测到重复创建时抛出，Controller 层统一捕获返回友好响应。
 */
public class IndexAlreadyExistsException extends RuntimeException {
    public IndexAlreadyExistsException(String message) {
        super(message);
    }
}
