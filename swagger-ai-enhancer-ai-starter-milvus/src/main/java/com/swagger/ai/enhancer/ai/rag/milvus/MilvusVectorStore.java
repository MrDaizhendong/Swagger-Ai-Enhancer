package com.swagger.ai.enhancer.ai.rag.milvus;

import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import com.swagger.ai.enhancer.ai.exception.DimensionMismatchException;
import com.swagger.ai.enhancer.ai.rag.VectorStoreProvider;
import com.swagger.ai.enhancer.ai.rag.IndexAlreadyExistsException;
import io.milvus.client.MilvusServiceClient;
import com.google.protobuf.ProtocolStringList;
import io.milvus.grpc.DataType;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.SearchResults;
import io.milvus.grpc.ShowCollectionsResponse;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.GetLoadStateParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.collection.ReleaseCollectionParam;
import io.milvus.param.collection.ShowCollectionsParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.DescribeIndexParam;
import io.milvus.response.DescCollResponseWrapper;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Milvus 向量存储实现。使用官方 milvus-sdk-java 2.5.x 直接操作 Milvus Standalone。
 * 字段：id（Int64, PK, autoID）/ content（VarChar）/ file_path（VarChar）
 *       / chunk_index（Int64）/ embedding（FloatVector）。
 *
 * 所有 API 均使用原生类型，不再使用反射，保证类型安全与可维护性。
 */
@Slf4j
public class MilvusVectorStore implements VectorStoreProvider {

    private final AiEnhancerProperties properties;
    private final MilvusServiceClient client;
    private final Map<String, Boolean> loadStateMap = new ConcurrentHashMap<>();

    public MilvusVectorStore(AiEnhancerProperties properties) {
        this.properties = properties;
        AiEnhancerProperties.MilvusConfig milvus = properties.getRag().getMilvus();
        log.info("连接 Milvus：{}:{}", milvus.getHost(), milvus.getPort());
        this.client = new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(milvus.getHost())
                .withPort(milvus.getPort())
                .build());
    }

    @Override
    public void createCollection(String collectionName, int dimension) {
        if (collectionExists(collectionName)) {
            // 已存在 → 检查维度是否匹配
            int oldDim = getCollectionDimension(collectionName);
            if (oldDim <= 0) {
                // 无法获取旧维度，降级：记录 warn 后跳过（不删除、不重建）
                log.warn("[milvus] 无法获取集合 {} 的维度信息，跳过创建（请手动检查维度匹配）",
                        collectionName);
                return;
            }
            if (oldDim == dimension) {
                log.info("[milvus] 集合 {} 已存在，维度 {} 匹配，跳过创建", collectionName, dimension);
                return;
            }
            // 维度不匹配 → 抛出特定异常，由上层返回可识别的状态码
            throw new DimensionMismatchException(collectionName, oldDim, dimension);
        }
        if (dimension <= 0) {
            throw new RuntimeException("向量维度未配置。请在 Swagger UI 中打开「AI 模型设置」面板，配置 Embedding 提供者和模型名称后点击保存，系统将自动探测模型维度。");
        }

        List<FieldType> fields = List.of(
                FieldType.newBuilder()
                        .withName("id")
                        .withDataType(DataType.Int64)
                        .withPrimaryKey(true)
                        .withAutoID(true)
                        .build(),
                FieldType.newBuilder()
                        .withName("content")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(65535)
                        .build(),
                FieldType.newBuilder()
                        .withName("file_path")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(1024)
                        .build(),
                FieldType.newBuilder()
                        .withName("chunk_index")
                        .withDataType(DataType.Int64)
                        .build(),
                FieldType.newBuilder()
                        .withName("embedding")
                        .withDataType(DataType.FloatVector)
                        .withDimension(dimension)
                        .build()
        );

        CreateCollectionParam param = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withDescription("Swagger AI knowledge base")
                .withShardsNum(1)
                .withFieldTypes(fields)
                .build();

        R<?> result = client.createCollection(param);
        if (result.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("创建 Milvus 集合失败：" + result.getMessage());
        }
        log.info("Milvus 集合 {} 创建成功（维度={}）", collectionName, dimension);
    }

    @Override
    public boolean collectionExists(String collectionName) {
        R<Boolean> result = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build());
        return result.getStatus() == R.Status.Success.getCode() && Boolean.TRUE.equals(result.getData());
    }

    @Override
    public void insert(String collectionName, List<VectorDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            log.info("插入文档为空，跳过");
            return;
        }
        if (!collectionExists(collectionName)) {
            createCollection(collectionName, properties.getRag().getDimension());
        }

        List<String> contentList = new ArrayList<>(docs.size());
        List<String> filePathList = new ArrayList<>(docs.size());
        List<Long> chunkIndexList = new ArrayList<>(docs.size());
        List<List<Float>> vectorsList = new ArrayList<>(docs.size());

        for (VectorDoc doc : docs) {
            contentList.add(doc.getContent() == null ? "" : doc.getContent());
            String filePath = "";
            long chunkIndex = 0L;
            if (doc.getMetadata() != null) {
                filePath = doc.getMetadata().getOrDefault("file_path", "");
                String ci = doc.getMetadata().get("chunk_index");
                if (ci != null) {
                    try {
                        chunkIndex = Long.parseLong(ci);
                    } catch (NumberFormatException ignored) {
                        // 保持默认 0
                    }
                }
            }
            filePathList.add(filePath);
            chunkIndexList.add(chunkIndex);

            List<Double> embedding = doc.getEmbedding() == null ? new ArrayList<>() : doc.getEmbedding();
            List<Float> floats = new ArrayList<>(embedding.size());
            for (Double v : embedding) {
                floats.add(v == null ? 0.0f : v.floatValue());
            }
            vectorsList.add(floats);
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("content", contentList));
        fields.add(new InsertParam.Field("file_path", filePathList));
        fields.add(new InsertParam.Field("chunk_index", chunkIndexList));
        fields.add(new InsertParam.Field("embedding", vectorsList));

        InsertParam param = InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build();

        R<?> result = client.insert(param);
        if (result.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("插入向量失败：" + result.getMessage());
        }
        log.info("已插入 {} 条向量到集合 {}", docs.size(), collectionName);
    }

    @Override
    public List<SearchResult> search(String collectionName, List<Double> queryVector,
                                     int topK, double minSimilarity) {
        if (!collectionExists(collectionName)) {
            log.warn("集合 {} 不存在，返回空结果", collectionName);
            return Collections.emptyList();
        }
        if (queryVector == null || queryVector.isEmpty()) {
            return Collections.emptyList();
        }

        List<Float> queryFloats = new ArrayList<>(queryVector.size());
        for (Double v : queryVector) {
            queryFloats.add(v == null ? 0.0f : v.floatValue());
        }
        List<List<Float>> vectorBatch = new ArrayList<>();
        vectorBatch.add(queryFloats);

        ensureLoadedAndIndexed(collectionName);

        SearchParam param = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withMetricType(MetricType.COSINE)
                .withTopK(topK)
                .withVectors(vectorBatch)
                .withVectorFieldName("embedding")
                .withParams("{\"nprobe\": 10}")
                .build();

        R<SearchResults> result = client.search(param);
        if (result.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("Milvus 搜索失败：" + result.getMessage());
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(result.getData().getResults());
        List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);

        // 过滤相似度低于阈值的结果，收集 id
        List<Long> hitIds = new ArrayList<>();
        List<Double> hitScores = new ArrayList<>();
        for (SearchResultsWrapper.IDScore idScore : idScores) {
            double score = idScore.getScore();
            if (score < minSimilarity) {
                continue;
            }
            hitIds.add(idScore.getLongID());
            hitScores.add(score);
        }
        if (hitIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 通过 query 反查 content / file_path 字段
        Map<Long, String> contentById = new HashMap<>();
        Map<Long, String> filePathById = new HashMap<>();
        try {
            QueryParam queryParam = QueryParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr("id in [" + joinLong(hitIds) + "]")
                    .withOutFields(List.of("content", "file_path"))
                    .build();
            R<QueryResults> qResult = client.query(queryParam);
            if (qResult.getStatus() == R.Status.Success.getCode() && qResult.getData() != null) {
                fillMilvusQueryResult(qResult.getData(), hitIds, contentById, filePathById);
            }
        } catch (Exception e) {
            log.warn("Milvus query 反查失败：{}，返回仅带 id 的结果", e.getMessage());
        }

        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < hitIds.size(); i++) {
            long id = hitIds.get(i);
            Map<String, String> metadata = new HashMap<>();
            if (filePathById.containsKey(id)) {
                metadata.put("file_path", filePathById.get(id));
            }
            SearchResult sr = SearchResult.builder()
                    .id(String.valueOf(id))
                    .content(contentById.getOrDefault(id, ""))
                    .score(hitScores.get(i))
                    .metadata(metadata)
                    .build();
            results.add(sr);
        }
        log.info("Milvus 检索完成，命中 {} 条（阈值 {}）", results.size(), minSimilarity);
        return results;
    }

    @Override
    public void deleteByFile(String collectionName, String filePath) {
        if (!collectionExists(collectionName) || filePath == null || filePath.isBlank()) {
            return;
        }
        String expr = "file_path == \"" + escapeQuotes(filePath) + "\"";
        R<?> result = client.delete(DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build());
        if (result.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("删除向量失败：" + result.getMessage());
        }
        log.info("已删除集合 {} 中文件路径等于 {} 的向量", collectionName, filePath);
    }

    @Override
    public void createIndex(String collectionName) {
        if (!collectionExists(collectionName)) {
            throw new RuntimeException("集合 " + collectionName + " 不存在，无法创建索引");
        }

        // —— 检查索引是否已存在 ——
        try {
            DescribeIndexParam describeParam = DescribeIndexParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFieldName("embedding")
                    .build();
            R<?> describeResult = client.describeIndex(describeParam);
            if (describeResult.getStatus() == R.Status.Success.getCode()) {
                log.info("[milvus] 集合 {} 的索引已存在，跳过重复创建", collectionName);
                throw new IndexAlreadyExistsException("集合 " + collectionName + " 的索引已存在");
            }
        } catch (IndexAlreadyExistsException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[milvus] 检查集合 {} 索引存在性失败，继续执行创建：{}",
                    collectionName, e.getMessage());
        }

        long start = System.currentTimeMillis();
        int nlist = Math.max(1, properties.getRag().getIndexNlist());

        CreateIndexParam param = CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("embedding")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\":" + nlist + "}")
                .build();

        R<?> r = client.createIndex(param);
        if (r.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("创建 Milvus 索引失败：" + r.getMessage());
        }
        long cost = System.currentTimeMillis() - start;
        log.info("Milvus 集合 {} 的索引已创建（IVF_FLAT, COSINE, nlist={}, cost={}ms）",
                collectionName, nlist, cost);
    }

    /** 索引已存在时抛出的异常（由通用 IndexAlreadyExistsException 处理，用于向前端返回 index_exists 状态） */
    @Override
    public void loadCollection(String collectionName) {
        if (!collectionExists(collectionName)) {
            throw new RuntimeException("集合 " + collectionName + " 不存在，无法加载");
        }
        long start = System.currentTimeMillis();
        LoadCollectionParam param = LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build();
        R<?> r = client.loadCollection(param);
        if (r.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("加载集合失败：" + r.getMessage());
        }
        long cost = System.currentTimeMillis() - start;
        log.info("Milvus 集合 {} 已加载到内存（{}ms）", collectionName, cost);
        loadStateMap.put(collectionName, true);
    }

    @Override
    public void releaseCollection(String collectionName) {
        if (!collectionExists(collectionName)) {
            log.warn("集合 {} 不存在，跳过释放", collectionName);
            return;
        }
        long start = System.currentTimeMillis();
        ReleaseCollectionParam param = ReleaseCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build();
        R<?> r = client.releaseCollection(param);
        if (r.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("释放集合失败：" + r.getMessage());
        }
        long cost = System.currentTimeMillis() - start;
        log.info("Milvus 集合 {} 已从内存释放（{}ms）", collectionName, cost);
        loadStateMap.put(collectionName, false);
    }

    // ============ 新增：Collection 列表 / 维度 / 删除（原生SDK调用）============

    @Override
    public List<String> getCollectionNames() {
        try {
            ShowCollectionsParam param = ShowCollectionsParam.newBuilder().build();
            R<ShowCollectionsResponse> result = client.showCollections(param);
            if (result.getStatus() != R.Status.Success.getCode() || result.getData() == null) {
                return Collections.emptyList();
            }
            ProtocolStringList names = result.getData().getCollectionNamesList();
            if (names == null || names.isEmpty()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(names);
        } catch (Exception e) {
            log.warn("[milvus] 获取 Collection 列表失败：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 获取指定 Collection 的向量字段维度（dimension）。
     * Collection 不存在或无法解析时返回 -1。
     */
    public int getCollectionDimension(String collectionName) {
        try {
            DescribeCollectionParam param = DescribeCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build();
            R<io.milvus.grpc.DescribeCollectionResponse> result = client.describeCollection(param);
            if (result.getStatus() != R.Status.Success.getCode() || result.getData() == null) {
                return -1;
            }
            DescCollResponseWrapper wrapper = new DescCollResponseWrapper(result.getData());
            FieldType vectorField = wrapper.getVectorField();
            if (vectorField == null) {
                return -1;
            }
            return vectorField.getDimension();
        } catch (Exception e) {
            log.warn("[milvus] 获取 Collection {} 的维度信息失败：{}", collectionName, e.getMessage());
            return -1;
        }
    }

    @Override
    public void dropCollection(String collectionName) {
        try {
            if (!collectionExists(collectionName)) {
                log.info("[milvus] 集合 {} 不存在，跳过删除", collectionName);
                return;
            }
            DropCollectionParam param = DropCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build();
            R<?> result = client.dropCollection(param);
            if (result.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("删除 Milvus 集合失败：" + result.getMessage());
            }
            log.info("[milvus] 已删除集合 {}", collectionName);
        } catch (Exception e) {
            log.error("[milvus] 删除集合 {} 失败：{}", collectionName, e.getMessage());
            throw new RuntimeException("删除 Milvus 集合失败：" + e.getMessage(), e);
        }
    }

    private static String escapeQuotes(String s) {
        return s.replace("\"", "\\\"");
    }

    private static String joinLong(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    /**
     * 用原生 QueryResultsWrapper 解析 query 反查结果。
     * 使用 milvus-sdk-java 2.5.x 的公共 API：getRowRecords()
     */
    private static void fillMilvusQueryResult(QueryResults queryResultsData,
                                              List<Long> hitIds,
                                              Map<Long, String> contentById,
                                              Map<Long, String> filePathById) {
        if (queryResultsData == null) return;
        try {
            QueryResultsWrapper wrapper = new QueryResultsWrapper(queryResultsData);
            List<QueryResultsWrapper.RowRecord> records = wrapper.getRowRecords();
            if (records == null || records.isEmpty()) return;
            for (int idx = 0; idx < hitIds.size() && idx < records.size(); idx++) {
                long id = hitIds.get(idx);
                QueryResultsWrapper.RowRecord rec = records.get(idx);
                Object contentVal = safeGet(rec, "content");
                Object fileVal = safeGet(rec, "file_path");
                contentById.put(id, contentVal == null ? "" : String.valueOf(contentVal));
                filePathById.put(id, fileVal == null ? "" : String.valueOf(fileVal));
            }
        } catch (Exception e) {
            log.warn("解析 Milvus query 反查结果失败：{}", e.getMessage());
        }
    }

    private static Object safeGet(QueryResultsWrapper.RowRecord record, String fieldName) {
        if (record == null) return null;
        try {
            return record.get(fieldName);
        } catch (Exception e) {
            return null;
        }
    }

    private void ensureLoadedAndIndexed(String collectionName) {
        try {
            client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build());
        } catch (Exception e) {
            log.warn("加载集合 {} 失败：{}", collectionName, e.getMessage());
        }
    }

    /**
     * 返回指定 Collection 是否已加载到内存。
     * 优先调用 Milvus 原生 getLoadState 交叉验证，失败时降级使用进程内的 loadStateMap。
     * 使用 getStateValue() 的 int 值比较以避免不同 SDK 版本枚举命名差异。
     */
    public boolean isLoaded(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) return false;

        // 先尝试 Milvus 原生 getLoadState 交叉验证，自动修正内存态
        try {
            GetLoadStateParam loadParam = GetLoadStateParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build();
            R<io.milvus.grpc.GetLoadStateResponse> loadResp = client.getLoadState(loadParam);
            log.info("[DEBUG-isLoaded] collection={}, rpcStatus={}, stateValue={}",
                    collectionName,
                    loadResp != null ? loadResp.getStatus() : "null",
                    (loadResp != null && loadResp.getData() != null)
                            ? loadResp.getData().getStateValue() : "N/A");
            if (loadResp != null && loadResp.getStatus() == R.Status.Success.getCode()
                    && loadResp.getData() != null) {
                // 0 = NotExist, 1 = NotLoad, 2 = Loading, 3 = Loaded
                int stateValue = loadResp.getData().getStateValue();
                if (stateValue == 3) {
                    loadStateMap.put(collectionName, true);
                    return true;
                }
                if (stateValue == 0 || stateValue == 1) {
                    loadStateMap.put(collectionName, false);
                    return false;
                }
                // 其他状态（Loading / UnKnown）记录 warn，走降级
                log.warn("[milvus] 集合 {} 加载状态值为 {}，降级使用内存态", collectionName, stateValue);
            }
        } catch (Exception e) {
            log.warn("[milvus] 查询集合 {} 加载状态失败（{}: {}），降级使用内存态",
                    collectionName, e.getClass().getSimpleName(), e.getMessage());
        }
        // 降级：使用进程内 loadStateMap
        return loadStateMap.getOrDefault(collectionName, false);
    }

    /**
     * 返回指定 Collection 的 embedding 字段索引是否存在且已构建完成（Finished）。
     * Milvus 索引创建是异步的；仅当索引状态 == IndexState.Finished（数字 3）时才认为可用。
     */
    public boolean indexExists(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) return false;
        try {
            DescribeIndexParam describeParam = DescribeIndexParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFieldName("embedding")
                    .build();
            R<io.milvus.grpc.DescribeIndexResponse> describeResult = client.describeIndex(describeParam);
            if (describeResult == null) return false;
            if (describeResult.getStatus() != R.Status.Success.getCode()) return false;
            io.milvus.grpc.DescribeIndexResponse data = describeResult.getData();
            if (data == null) return false;
            int count = data.getIndexDescriptionsCount();
            if (count <= 0) return false;
            // 0 = None, 1 = Unissued, 2 = InProgress, 3 = Finished, 4 = Failed
            for (int i = 0; i < count; i++) {
                io.milvus.grpc.IndexDescription desc = data.getIndexDescriptions(i);
                if (desc == null) continue;
                int stateValue = desc.getStateValue();
                if (stateValue == 3) {
                    return true;
                }
            }
            log.info("[milvus] 集合 {} 的索引存在但未构建完成（状态值={}）", collectionName,
                    data.getIndexDescriptions(0).getStateValue());
            return false;
        } catch (Exception e) {
            log.warn("[milvus] 检查集合 {} 索引存在性失败：{}", collectionName, e.getMessage());
            return false;
        }
    }

    // 预留：对外暴露关闭连接操作
    public void close() {
        try {
            client.close();
            log.info("Milvus 连接已关闭");
        } catch (Exception e) {
            log.warn("关闭 Milvus 连接失败：{}", e.getMessage());
        }
    }
}
