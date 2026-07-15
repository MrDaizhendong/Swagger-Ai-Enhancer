package com.swagger.ai.enhancer.ai.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 向量存储提供者接口：定义对向量数据库的增删查操作。
 * 所有实现应保持幂等：可重复调用、失败可重试。
 */
public interface VectorStoreProvider {

    /**
     * 创建集合（若已存在则不抛出异常，直接返回）。
     *
     * @param collectionName 集合名称
     * @param dimension      向量维度
     */
    void createCollection(String collectionName, int dimension);

    /**
     * 检查集合是否已存在。
     *
     * @param collectionName 集合名称
     * @return true 表示已存在
     */
    boolean collectionExists(String collectionName);

    /**
     * 获取当前向量数据库中所有 Collection 的名称列表。
     * 若不支持（或查询异常），返回空列表。
     *
     * @return 所有 Collection 名称列表
     */
    java.util.List<String> getCollectionNames();

    /**
     * 删除指定 Collection（整表 / 整集合）。
     * 若集合不存在，应静默返回而不抛异常。
     *
     * @param collectionName 集合名称
     */
    void dropCollection(String collectionName);

    /**
     * 批量插入向量文档。
     *
     * @param collectionName 集合名称
     * @param docs           文档列表
     */
    void insert(String collectionName, List<VectorDoc> docs);

    /**
     * 按向量进行相似度检索，返回最相似的 topK 个结果。
     *
     * @param collectionName  集合名称
     * @param queryVector     查询向量
     * @param topK            返回条数
     * @param minSimilarity   最小相似度（0.0 ~ 1.0），低于此值被过滤
     * @return 检索结果（按相似度从高到低排序）
     */
    List<SearchResult> search(String collectionName, List<Double> queryVector,
                              int topK, double minSimilarity);

    /**
     * 删除指定文件对应的所有向量（用于增量同步时清理旧数据）。
     *
     * @param collectionName 集合名称
     * @param filePath       文件路径（作为元数据条件）
     */
    void deleteByFile(String collectionName, String filePath);

    /**
     * 为指定集合在向量字段上创建索引。
     *
     * @param collectionName 集合名称
     */
    void createIndex(String collectionName);

    /**
     * 将指定集合加载到内存中。加载成功后才可进行向量搜索。
     *
     * @param collectionName 集合名称
     */
    void loadCollection(String collectionName);

    /**
     * 从内存中释放指定集合。释放后该集合不可进行向量搜索，可节省内存。
     *
     * @param collectionName 集合名称
     */
    void releaseCollection(String collectionName);

    /**
     * 检查指定集合的索引是否已创建。
     * 默认实现返回 true（非 Milvus 存储：索引随表/集合存在）。
     * Milvus 等需要单独建索引的实现应覆盖此方法。
     *
     * @param collectionName 集合名称
     * @return true 表示索引已存在
     */
    default boolean indexExists(String collectionName) {
        return true;
    }

    /**
     * 检查指定集合是否已加载到内存中。
     * 默认实现返回 true（非 Milvus 存储：集合默认视为已加载）。
     * Milvus 等需要显式 load/release 的实现应覆盖此方法。
     *
     * @param collectionName 集合名称
     * @return true 表示已加载到内存
     */
    default boolean isLoaded(String collectionName) {
        return true;
    }

    /**
     * 向量文档：一段文本及其向量和元数据。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class VectorDoc {
        /**
         * 唯一 ID；为空时由实现层生成。
         */
        private String id;
        /**
         * 原始文本内容。
         */
        private String content;
        /**
         * 文本对应的向量。
         */
        private List<Double> embedding;
        /**
         * 元数据，例如 file_path、chunk_index 等。
         */
        private Map<String, String> metadata;
    }

    /**
     * 检索结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class SearchResult {
        private String id;
        private String content;
        private double score;
        private Map<String, String> metadata;
    }
}
