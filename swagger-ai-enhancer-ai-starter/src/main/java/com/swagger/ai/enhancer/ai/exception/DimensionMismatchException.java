package com.swagger.ai.enhancer.ai.exception;

/**
 * Collection 维度不匹配异常：
 *   当用户更换 Embedding 模型导致向量维度变化时，已存在的 Collection
 *   不能自动删除重建，而是抛出此异常，由上层返回给前端一个可识别的状态。
 *
 * 字段：
 *   collectionName — 集合名称
 *   oldDimension   — 旧 Collection 实际维度
 *   newDimension   — 新配置中的目标维度
 */
public class DimensionMismatchException extends RuntimeException {

    private final String collectionName;
    private final int oldDimension;
    private final int newDimension;

    public DimensionMismatchException(String collectionName, int oldDimension, int newDimension) {
        super("向量维度不匹配：集合 [" + collectionName + "] 的维度为 " + oldDimension
                + "，而当前配置的维度为 " + newDimension + "。请删除旧集合后重新同步。");
        this.collectionName = collectionName;
        this.oldDimension = oldDimension;
        this.newDimension = newDimension;
    }

    public String getCollectionName() { return collectionName; }
    public int getOldDimension() { return oldDimension; }
    public int getNewDimension() { return newDimension; }
}
