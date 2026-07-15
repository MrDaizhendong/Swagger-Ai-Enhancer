package com.swagger.ai.enhancer.ai.rag.weaviate;

import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import com.swagger.ai.enhancer.ai.rag.IndexAlreadyExistsException;
import com.swagger.ai.enhancer.ai.rag.NotApplicableForVectorStoreException;
import com.swagger.ai.enhancer.ai.rag.VectorStoreProvider;
import io.weaviate.client.Config;
import io.weaviate.client.WeaviateAuthClient;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import io.weaviate.client.base.WeaviateError;
import io.weaviate.client.base.WeaviateErrorMessage;
import io.weaviate.client.v1.auth.exception.AuthException;
import io.weaviate.client.v1.batch.model.ObjectGetResponse;
import io.weaviate.client.v1.data.model.WeaviateObject;
import io.weaviate.client.v1.filters.Operator;
import io.weaviate.client.v1.filters.WhereFilter;
import io.weaviate.client.v1.graphql.model.GraphQLResponse;
import io.weaviate.client.v1.graphql.query.argument.NearVectorArgument;
import io.weaviate.client.v1.graphql.query.fields.Field;
import io.weaviate.client.v1.misc.model.VectorIndexConfig;
import io.weaviate.client.v1.schema.model.Property;
import io.weaviate.client.v1.schema.model.WeaviateClass;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Weaviate 向量存储实现（基于官方 Java client 4.8.x）。
 *
 * <p>在 Weaviate 中：
 * <ul>
 *   <li>"Class" 对应其他向量数据库的 Collection（我们用 WeaviateConfig.getCollectionName() 作为类名）；</li>
 *   <li>"Properties" 对应对象的标量字段（这里存储 content / filePath / chunkIndex）；</li>
 *   <li>"Vector" 对应对象的向量表示，统一使用 {@code vector} 作为字段名。</li>
 * </ul>
 */
@Slf4j
public class WeaviateVectorStore implements VectorStoreProvider {

    private final AiEnhancerProperties properties;
    private final WeaviateClient client;

    public WeaviateVectorStore(AiEnhancerProperties properties) {
        this.properties = properties;
        AiEnhancerProperties.WeaviateConfig cfg = properties.getRag().getWeaviate();
        Config config = new Config("http", cfg.getHost() + ":" + cfg.getPort());
        if (cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) {
            try {
                this.client = WeaviateAuthClient.apiKey(config, cfg.getApiKey());
            } catch (AuthException e) {
                throw new RuntimeException("Weaviate 认证初始化失败：" + e.getMessage(), e);
            }
        } else {
            this.client = new WeaviateClient(config);
        }
        log.info("连接 Weaviate：{}:{}", cfg.getHost(), cfg.getPort());
    }

    // ========================================================================
    // Schema
    // ========================================================================

    @Override
    public void createCollection(String collectionName, int dimension) {
        String className = toClassName(collectionName);
        if (collectionExists(collectionName)) {
            log.info("Weaviate Class {} 已存在，跳过创建", className);
            return;
        }

        Property contentProp = Property.builder()
                .name("content")
                .dataType(List.of("text"))
                .build();
        Property filePathProp = Property.builder()
                .name("filePath")
                .dataType(List.of("string"))
                .build();
        Property chunkIndexProp = Property.builder()
                .name("chunkIndex")
                .dataType(List.of("int"))
                .build();

        // 距离度量：cosine（等价于 "cosine"，Weaviate 支持 cosine/dot/l2-squared/ham）
        VectorIndexConfig indexConfig = VectorIndexConfig.builder()
                .distance("cosine")
                .vectorCacheMaxObjects(100000L)
                .build();

        WeaviateClass weaviateClass = WeaviateClass.builder()
                .className(className)
                .vectorIndexType("hnsw")
                .vectorIndexConfig(indexConfig)
                .properties(List.of(contentProp, filePathProp, chunkIndexProp))
                .build();

        Result<Boolean> result = client.schema().classCreator()
                .withClass(weaviateClass)
                .run();
        if (result.hasErrors()) {
            throw new RuntimeException("创建 Weaviate Class 失败：" + errorToString(result.getError()));
        }
        log.info("Weaviate Class {} 创建成功（维度={}）", className, dimension);
    }

    @Override
    public boolean collectionExists(String collectionName) {
        String className = toClassName(collectionName);
        Result<WeaviateClass> result = client.schema().classGetter()
                .withClassName(className)
                .run();
        return result.getResult() != null;
    }

    // ========================================================================
    // Insert
    // ========================================================================

    @Override
    public void insert(String collectionName, List<VectorDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            log.info("插入文档为空，跳过");
            return;
        }
        String className = toClassName(collectionName);
        if (!collectionExists(collectionName)) {
            createCollection(collectionName, properties.getRag().getDimension());
        }

        int batchSize = 100;
        int total = docs.size();
        int success = 0;
        int failures = 0;

        for (int start = 0; start < total; start += batchSize) {
            int end = Math.min(start + batchSize, total);
            List<WeaviateObject> batch = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                VectorDoc doc = docs.get(i);
                Map<String, Object> props = new HashMap<>();
                props.put("content", doc.getContent() == null ? "" : doc.getContent());
                String filePath = "";
                int chunkIndex = 0;
                if (doc.getMetadata() != null) {
                    filePath = doc.getMetadata().getOrDefault("file_path", "");
                    String ci = doc.getMetadata().get("chunk_index");
                    if (ci != null) {
                        try {
                            chunkIndex = Integer.parseInt(ci);
                        } catch (NumberFormatException ignored) {
                            // keep 0
                        }
                    }
                }
                props.put("filePath", filePath);
                props.put("chunkIndex", chunkIndex);

                Float[] vector = toFloatArray(doc.getEmbedding());

                WeaviateObject obj = WeaviateObject.builder()
                        .className(className)
                        .properties(props)
                        .vector(vector)
                        .build();
                batch.add(obj);
            }

            Result<ObjectGetResponse[]> batchResult = client.batch().objectsBatcher()
                    .withObjects(batch.toArray(new WeaviateObject[0]))
                    .run();
            if (batchResult.hasErrors()) {
                failures += batch.size();
                log.warn("Weaviate 批量插入失败：{}", errorToString(batchResult.getError()));
                continue;
            }
            success += batch.size();
        }

        log.info("已插入 {} 条对象到 Weaviate Class {}（失败 {} 条）", success, className, failures);
    }

    // ========================================================================
    // Search
    // ========================================================================

    @Override
    public List<SearchResult> search(String collectionName, List<Double> queryVector,
                                     int topK, double minSimilarity) {
        if (!collectionExists(collectionName)) {
            log.warn("Weaviate Class {} 不存在，返回空结果", collectionName);
            return Collections.emptyList();
        }
        if (queryVector == null || queryVector.isEmpty()) {
            return Collections.emptyList();
        }
        String className = toClassName(collectionName);
        Float[] vector = toFloatArray(queryVector);

        NearVectorArgument nearVector = NearVectorArgument.builder()
                .vector(vector)
                .certainty((float) minSimilarity)
                .build();

        Result<GraphQLResponse> result = client.graphQL().get()
                .withClassName(className)
                .withNearVector(nearVector)
                .withLimit(topK)
                .withFields(
                        Field.builder().name("content").build(),
                        Field.builder().name("filePath").build(),
                        Field.builder().name("chunkIndex").build(),
                        Field.builder().name("_additional")
                                .fields(new Field[]{
                                        Field.builder().name("id").build(),
                                        Field.builder().name("certainty").build()
                                })
                                .build()
                )
                .run();

        if (result.hasErrors()) {
            log.warn("Weaviate 检索失败：{}", errorToString(result.getError()));
            return Collections.emptyList();
        }
        if (result.getResult() == null || result.getResult().getData() == null) {
            return Collections.emptyList();
        }

        Object data = ((Map<?, ?>) result.getResult().getData()).get("Get");
        if (data == null) {
            return Collections.emptyList();
        }
        Object classObj = ((Map<?, ?>) data).get(className);
        if (!(classObj instanceof List<?>)) {
            return Collections.emptyList();
        }

        List<SearchResult> hits = new ArrayList<>();
        for (Object item : (List<?>) classObj) {
            if (!(item instanceof Map<?, ?>)) continue;
            Map<?, ?> map = (Map<?, ?>) item;
            Object additional = map.get("_additional");
            double score = 0.0;
            String id = null;
            if (additional instanceof Map<?, ?>) {
                Map<?, ?> a = (Map<?, ?>) additional;
                if (a.get("id") != null) id = String.valueOf(a.get("id"));
                if (a.get("certainty") != null) {
                    try {
                        score = Double.parseDouble(String.valueOf(a.get("certainty")));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            if (score < minSimilarity) continue;

            String content = map.get("content") == null ? "" : String.valueOf(map.get("content"));
            String filePath = map.get("filePath") == null ? "" : String.valueOf(map.get("filePath"));

            Map<String, String> metadata = new HashMap<>();
            metadata.put("file_path", filePath);

            hits.add(SearchResult.builder()
                    .id(id)
                    .content(content)
                    .score(score)
                    .metadata(metadata)
                    .build());
        }

        log.info("Weaviate 检索完成，命中 {} 条（阈值 {}）", hits.size(), minSimilarity);
        return hits;
    }

    // ========================================================================
    // Delete by file
    // ========================================================================

    @Override
    public void deleteByFile(String collectionName, String filePath) {
        if (!collectionExists(collectionName) || filePath == null || filePath.isBlank()) {
            return;
        }
        String className = toClassName(collectionName);
        WhereFilter where = WhereFilter.builder()
                .path(new String[]{"filePath"})
                .operator(Operator.Equal)
                .valueString(filePath)
                .build();

        Result<?> result = client.batch().objectsBatchDeleter()
                .withClassName(className)
                .withWhere(where)
                .run();

        if (result.hasErrors()) {
            log.warn("Weaviate 按 filePath 删除失败：{}", errorToString(result.getError()));
            return;
        }
        log.info("已删除 Weaviate Class {} 中 filePath={} 的所有对象", className, filePath);
    }

    // ========================================================================
    // Lifecycle (Weaviate 自动索引 / 默认常驻内存，无需显式调用)
    // ========================================================================

    @Override
    public void createIndex(String collectionName) {
        String className = toClassName(collectionName);
        if (!collectionExists(collectionName)) {
            throw new RuntimeException("Weaviate Class " + className
                    + " 不存在，不能为不存在的 Class 创建索引（请先执行知识库同步）");
        }
        log.info("Weaviate Class {}：使用 hnsw 向量索引，创建 Class 时已自动配置，无需单独 createIndex",
                className);
        throw new IndexAlreadyExistsException(
                "Weaviate Class " + className + " 索引已自动配置，无需重复创建");
    }

    @Override
    public void loadCollection(String collectionName) {
        String className = toClassName(collectionName);
        if (!collectionExists(collectionName)) {
            throw new RuntimeException("Weaviate Class " + className
                    + " 不存在，无法加载");
        }
        throw new NotApplicableForVectorStoreException(
                "Weaviate Class " + className + "：由服务端管理冷热分层，不支持显式 load");
    }

    @Override
    public void releaseCollection(String collectionName) {
        String className = toClassName(collectionName);
        if (!collectionExists(collectionName)) {
            throw new RuntimeException("Weaviate Class " + className
                    + " 不存在，无法释放");
        }
        throw new NotApplicableForVectorStoreException(
                "Weaviate Class " + className + "：由服务端管理冷热分层，不支持显式 release");
    }

    @Override
    public java.util.List<String> getCollectionNames() {
        try {
            Result<?> result = (Result<?>) client.schema().getter().run();
            if (result == null || result.hasErrors()) {
                String msg = result == null ? "null" : errorToString(result.getError());
                log.warn("Weaviate 读取 Class 列表失败：{}", msg);
                return java.util.Collections.emptyList();
            }
            Object schema = result.getResult();
            if (schema == null) return java.util.Collections.emptyList();
            return parseWeaviateClassList(schema);
        } catch (Exception e) {
            log.warn("Weaviate 读取 Class 列表失败：{}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 尽力解析 schema.getter() 返回对象中的 classes 列表，兼容不同版本的 Java SDK。
     * 尝试按以下顺序读取：
     *   schema.getClasses() -> List<WeaviateClass>  → 取 className/class 属性
     */
    private java.util.List<String> parseWeaviateClassList(Object schema) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            // 查找 getClasses() 方法
            java.lang.reflect.Method getClasses = null;
            for (java.lang.reflect.Method m : schema.getClass().getMethods()) {
                if ("getClasses".equals(m.getName()) && m.getParameterCount() == 0) {
                    getClasses = m;
                    break;
                }
            }
            if (getClasses == null) {
                // 回退：通过 schema().getter().run().getResult() 可能返回 List<String>
                log.warn("Weaviate schema 对象未提供 getClasses 方法，类型={}",
                        schema.getClass().getName());
                return java.util.Collections.emptyList();
            }
            Object classesVal = getClasses.invoke(schema);
            if (classesVal instanceof java.util.List<?>) {
                for (Object item : (java.util.List<?>) classesVal) {
                    if (item == null) continue;
                    if (item instanceof String) {
                        names.add((String) item);
                    } else {
                        try {
                            java.lang.reflect.Method getCn = item.getClass().getMethod("getClassName");
                            Object v = getCn.invoke(item);
                            if (v instanceof String) names.add((String) v);
                            else names.add(item.toString());
                        } catch (NoSuchMethodException e) {
                            // 尝试 getClass / getClass()
                            try {
                                java.lang.reflect.Method getC = item.getClass().getMethod("getClass");
                                Object v = getC.invoke(item);
                                if (v instanceof String) names.add((String) v);
                                else names.add(item.toString());
                            } catch (Exception ignored) {
                                names.add(item.toString());
                            }
                        } catch (Exception ignored) {
                            names.add(item.toString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 Weaviate Class 列表失败：{}", e.getMessage());
        }
        return names;
    }

    @Override
    public void dropCollection(String collectionName) {
        String className = toClassName(collectionName);
        try {
            Result<?> result = (Result<?>) client.schema().classDeleter()
                    .withClassName(className)
                    .run();
            if (result == null || result.hasErrors()) {
                String msg = result == null ? "null" : errorToString(result.getError());
                throw new RuntimeException("Weaviate 删除 Class " + className + " 失败：" + msg);
            }
            log.info("Weaviate Class {} 已删除", className);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Weaviate 删除 Class " + className + " 失败：" + e.getMessage(), e);
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static String toClassName(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            return "SwaggerKnowledge";
        }
        StringBuilder sb = new StringBuilder(collectionName.length());
        boolean upperNext = true;
        for (int i = 0; i < collectionName.length(); i++) {
            char c = collectionName.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(upperNext ? Character.toUpperCase(c) : c);
                upperNext = false;
            } else {
                upperNext = true;
            }
        }
        String name = sb.toString();
        if (name.isEmpty()) return "SwaggerKnowledge";
        return name;
    }

    private static Float[] toFloatArray(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) return new Float[0];
        Float[] out = new Float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            Double v = embedding.get(i);
            out[i] = v == null ? 0.0f : v.floatValue();
        }
        return out;
    }

    private static String errorToString(WeaviateError error) {
        if (error == null) return "<null>";
        StringBuilder sb = new StringBuilder();
        sb.append("status=").append(error.getStatusCode());
        if (error.getMessages() != null) {
            for (WeaviateErrorMessage msg : error.getMessages()) {
                if (msg != null) sb.append(" msg=").append(msg.getMessage());
            }
        }
        return sb.toString();
    }
}
