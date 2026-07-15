package com.swagger.ai.enhancer.ai.rag.qdrant;

import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import com.swagger.ai.enhancer.ai.rag.IndexAlreadyExistsException;
import com.swagger.ai.enhancer.ai.rag.NotApplicableForVectorStoreException;
import com.swagger.ai.enhancer.ai.rag.VectorStoreProvider;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Points;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Qdrant 向量存储实现。
 * 关键要点：
 * - payload 通过 putAllPayload(Map<String, Value>) 存储
 * - 集合创建使用 Collections.VectorParams（Cosine 距离）
 * - searchAsync 返回 List<ScoredPoint>
 * 为跨版本兼容性，部分点结构通过反射创建。
 */
@Slf4j
public class QdrantVectorStore implements VectorStoreProvider {

    private final AiEnhancerProperties properties;
    private final QdrantClient client;

    public QdrantVectorStore(AiEnhancerProperties properties) {
        this.properties = properties;
        AiEnhancerProperties.QdrantConfig q = properties.getRag().getQdrant();
        log.info("连接 Qdrant：{}:{}（tls={}）", q.getHost(), q.getPort(), q.isUseTls());
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(q.getHost(), q.getPort(), q.isUseTls());
        if (q.getApiKey() != null && !q.getApiKey().isBlank()) {
            builder.withApiKey(q.getApiKey());
        }
        this.client = new QdrantClient(builder.build());
    }

    @Override
    public void createCollection(String collectionName, int dimension) {
        try {
            if (collectionExists(collectionName)) {
                log.info("Qdrant 集合 {} 已存在，跳过创建", collectionName);
                return;
            }
            Object vectorParams = buildVectorParams(dimension);
            // 使用反射调用 createCollectionAsync(String collectionName, VectorParams vectorParams)
            Method m = client.getClass().getMethod("createCollectionAsync", String.class, vectorParams.getClass());
            @SuppressWarnings("unchecked")
            Future<Object> future = (Future<Object>) m.invoke(client, collectionName, vectorParams);
            future.get(60, TimeUnit.SECONDS);
            log.info("Qdrant 集合 {} 创建成功（维度={}）", collectionName, dimension);
        } catch (Exception e) {
            throw new RuntimeException("创建 Qdrant 集合失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean collectionExists(String collectionName) {
        try {
            Future<?> future = client.collectionExistsAsync(collectionName);
            Object result = future.get(10, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("检查 Qdrant 集合状态失败：{}", e.getMessage());
            return false;
        }
    }

    @Override
    public void insert(String collectionName, List<VectorDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        try {
            if (!collectionExists(collectionName)) {
                createCollection(collectionName, properties.getRag().getDimension());
            }
            List<Object> points = new ArrayList<>(docs.size());
            for (int i = 0; i < docs.size(); i++) {
                VectorDoc doc = docs.get(i);
                Map<String, Object> payload = new HashMap<>();
                payload.put("content", doc.getContent() == null ? "" : doc.getContent());
                if (doc.getMetadata() != null) {
                    if (doc.getMetadata().get("file_path") != null) {
                        payload.put("file_path", doc.getMetadata().get("file_path"));
                    }
                    if (doc.getMetadata().get("chunk_index") != null) {
                        try {
                            payload.put("chunk_index", Long.parseLong(doc.getMetadata().get("chunk_index")));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                Object point = buildPoint(generatePointId(doc, i), toFloatList(doc.getEmbedding()), payload);
                points.add(point);
            }

            // client.upsertAsync(String, List<PointStruct>)
            Object[] args = new Object[]{collectionName, points};
            Method[] methods = client.getClass().getMethods();
            Method upsert = null;
            for (Method method : methods) {
                if ("upsertAsync".equals(method.getName()) && method.getParameterCount() == 2) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params[0] == String.class) {
                        upsert = method;
                        break;
                    }
                }
            }
            if (upsert == null) {
                throw new RuntimeException("找不到 upsertAsync 方法");
            }
            @SuppressWarnings("unchecked")
            Future<Object> future = (Future<Object>) upsert.invoke(client, args);
            future.get(60, TimeUnit.SECONDS);
            log.info("Qdrant 已 upsert {} 点到集合 {}", points.size(), collectionName);
        } catch (Exception e) {
            throw new RuntimeException("插入 Qdrant 点失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<SearchResult> search(String collectionName, List<Double> queryVector,
                                     int topK, double minSimilarity) {
        if (!collectionExists(collectionName) || queryVector == null || queryVector.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        try {
            List<Float> floats = toFloatList(queryVector);
            // 不使用 Filter，直接通过 Points.SearchPoints 构造请求
            Points.SearchPoints.Builder sb = Points.SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(floats)
                    .setLimit(topK);
            // 高版本 qdrant-client 支持 setWithPayloadSelector / setWithPayload，这里用反射匹配
            try {
                java.lang.reflect.Method m1 = sb.getClass()
                        .getMethod("setWithPayloadSelector", Points.WithPayloadSelector.class);
                m1.invoke(sb, Points.WithPayloadSelector.newBuilder().setEnable(true).build());
            } catch (NoSuchMethodException ignored1) {
                try {
                    java.lang.reflect.Method m2 = sb.getClass().getMethod("setWithPayload", boolean.class);
                    m2.invoke(sb, true);
                } catch (Exception ignored2) {
                }
            }
            Object searchResult = client.searchAsync(sb.build()).get(30, TimeUnit.SECONDS);

            // 预期返回 List<ScoredPoint>
            List<SearchResult> results = new ArrayList<>();
            if (searchResult instanceof Iterable<?>) {
                for (Object rawPoint : (Iterable<?>) searchResult) {
                    SearchResult sr = toSearchResult(rawPoint, minSimilarity);
                    if (sr != null) {
                        results.add(sr);
                    }
                }
            }
            log.info("Qdrant 检索完成，命中 {} 条（阈值 {}）", results.size(), minSimilarity);
            return results;
        } catch (Exception e) {
            throw new RuntimeException("Qdrant 搜索失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteByFile(String collectionName, String filePath) {
        if (!collectionExists(collectionName) || filePath == null || filePath.isBlank()) {
            return;
        }
        try {
            Points.Filter filter = Points.Filter.newBuilder()
                    .addMust(Points.Condition.newBuilder()
                            .setField(Points.FieldCondition.newBuilder()
                                    .setKey("file_path")
                                    .setMatch(Points.Match.newBuilder()
                                            .setKeyword(filePath)
                                            .build())
                                    .build())
                            .build())
                    .build();
            // 用反射查找可用的 deleteAsync 方法
            Object[] args = new Object[]{collectionName, filter};
            java.lang.reflect.Method[] methods = client.getClass().getMethods();
            java.lang.reflect.Method deleteMethod = null;
            for (java.lang.reflect.Method m : methods) {
                if ("deleteAsync".equals(m.getName()) && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == String.class) {
                    deleteMethod = m;
                    break;
                }
            }
            if (deleteMethod == null) {
                throw new RuntimeException("找不到 deleteAsync 方法");
            }
            @SuppressWarnings("unchecked")
            Future<Object> future = (Future<Object>) deleteMethod.invoke(client, args);
            future.get(30, TimeUnit.SECONDS);
            log.info("Qdrant 已删除集合 {} 中 file_path={} 的点", collectionName, filePath);
        } catch (Exception e) {
            throw new RuntimeException("Qdrant 删除点失败：" + e.getMessage(), e);
        }
    }

    private static Object buildVectorParams(int dimension) throws Exception {
        // 查找 io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
        Class<?> builderClass = Class.forName("io.qdrant.client.grpc.Collections$VectorParams$Builder");
        Class<?> paramsClass = Class.forName("io.qdrant.client.grpc.Collections$VectorParams");
        Object builder = paramsClass.getMethod("newBuilder").invoke(null);

        builderClass.getMethod("setSize", long.class).invoke(builder, (long) dimension);
        // setDistance(Collections.Distance.Cosine)
        Class<?> distanceEnum = Class.forName("io.qdrant.client.grpc.Collections$Distance");
        Object cosine = ((Object[]) distanceEnum.getMethod("values").invoke(null))[0]; // 默认第一个可能是 Unknown
        // 正确获取 Cosine
        for (Object val : (Object[]) distanceEnum.getMethod("values").invoke(null)) {
            if ("Cosine".equals(String.valueOf(val))) {
                cosine = val;
                break;
            }
        }
        builderClass.getMethod("setDistance", distanceEnum).invoke(builder, cosine);
        Method build = builderClass.getMethod("build");
        return build.invoke(builder);
    }

    private static Object buildPoint(long idNum, List<Float> vec, Map<String, Object> payload) throws Exception {
        // 使用 Points.PointStruct.newBuilder()
        Class<?> builderClass = Class.forName("io.qdrant.client.grpc.Points$PointStruct$Builder");
        Class<?> pointClass = Class.forName("io.qdrant.client.grpc.Points$PointStruct");
        Object builder = pointClass.getMethod("newBuilder").invoke(null);

        // setId(PointsId)
        Class<?> pointsIdClass = Class.forName("io.qdrant.client.grpc.Points$PointsId");
        Object pointsIdBuilder = pointsIdClass.getMethod("newBuilder").invoke(null);
        pointsIdBuilder.getClass().getMethod("setNum", long.class).invoke(pointsIdBuilder, idNum);
        Object pointsId = pointsIdBuilder.getClass().getMethod("build").invoke(pointsIdBuilder);
        builderClass.getMethod("setId", pointsIdClass).invoke(builder, pointsId);

        // addAllVectors(List<Float>)
        builderClass.getMethod("addAllVectors", Iterable.class).invoke(builder, vec);

        // putAllPayload(Map<String, Value>)
        Object payloadMap = buildPayloadValueMap(payload);
        try {
            builderClass.getMethod("putAllPayload", Map.class).invoke(builder, payloadMap);
        } catch (NoSuchMethodException e) {
            // 回退：忽略 payload（依然可以插入向量）
            log.warn("Qdrant builder 不支持 putAllPayload，忽略 payload");
        }

        return builderClass.getMethod("build").invoke(builder);
    }

    private static Map<Object, Object> buildPayloadValueMap(Map<String, Object> payload) throws Exception {
        Map<Object, Object> result = new HashMap<>();
        Class<?> valueClass = Class.forName("io.qdrant.client.grpc.Points$Value");
        Object valueBuilder;
        try {
            valueBuilder = valueClass.getMethod("newBuilder").invoke(null);
        } catch (NoSuchMethodException e) {
            // 也许叫 newBuilder 但包名不同，退回使用反射
            return result;
        }
        Class<?> valueBuilderClass = valueBuilder.getClass();
        Method setString;
        Method setInteger;
        Method buildValue;
        try {
            setString = valueBuilderClass.getMethod("setStringValue", String.class);
            setInteger = valueBuilderClass.getMethod("setIntegerValue", long.class);
            buildValue = valueBuilderClass.getMethod("build");
        } catch (NoSuchMethodException e) {
            log.warn("Qdrant Value builder 方法缺失，忽略 payload");
            return result;
        }

        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            Object value;
            if (entry.getValue() instanceof Number) {
                Object b2 = valueClass.getMethod("newBuilder").invoke(null);
                setInteger.invoke(b2, ((Number) entry.getValue()).longValue());
                value = buildValue.invoke(b2);
            } else {
                Object b2 = valueClass.getMethod("newBuilder").invoke(null);
                setString.invoke(b2, String.valueOf(entry.getValue()));
                value = buildValue.invoke(b2);
            }
            result.put(entry.getKey(), value);
        }
        return result;
    }

    private static SearchResult toSearchResult(Object scoredPoint, double minSimilarity) {
        if (scoredPoint == null) return null;
        Class<?> cls = scoredPoint.getClass();
        try {
            Method getScore = cls.getMethod("getScore");
            double score = ((Number) getScore.invoke(scoredPoint)).doubleValue();
            if (score < minSimilarity) {
                return null;
            }
            String id = "";
            Map<String, String> metadata = new HashMap<>();
            String content = "";
            // id
            try {
                Method getId = cls.getMethod("getId");
                Object pid = getId.invoke(scoredPoint);
                if (pid != null) {
                    try {
                        Method getNum = pid.getClass().getMethod("getNum");
                        long n = ((Number) getNum.invoke(pid)).longValue();
                        id = String.valueOf(n);
                    } catch (NoSuchMethodException ignored) {
                        try {
                            Method getUuid = pid.getClass().getMethod("getUuid");
                            id = String.valueOf(getUuid.invoke(pid));
                        } catch (NoSuchMethodException ignored2) {
                            id = pid.toString();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            // payload
            try {
                Method getPayload = cls.getMethod("getPayloadMap");
                @SuppressWarnings("unchecked")
                Map<Object, Object> payloadMap = (Map<Object, Object>) getPayload.invoke(scoredPoint);
                for (Map.Entry<Object, Object> entry : payloadMap.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    Object val = entry.getValue();
                    String s = "";
                    try {
                        Method getString = val.getClass().getMethod("getStringValue");
                        s = String.valueOf(getString.invoke(val));
                    } catch (Exception ignored) {
                        try {
                            Method getInt = val.getClass().getMethod("getIntegerValue");
                            s = String.valueOf(getInt.invoke(val));
                        } catch (Exception ignored2) {
                            s = String.valueOf(val);
                        }
                    }
                    if ("content".equals(key)) {
                        content = s;
                    } else {
                        metadata.put(key, s);
                    }
                }
            } catch (Exception ignored) {
            }
            return SearchResult.builder()
                    .id(id)
                    .content(content)
                    .score(score)
                    .metadata(metadata)
                    .build();
        } catch (Exception e) {
            log.warn("解析 ScoredPoint 失败：{}", e.getMessage());
            return null;
        }
    }

    private static List<Float> toFloatList(List<Double> embedding) {
        List<Float> floats = new ArrayList<>(embedding == null ? 0 : embedding.size());
        if (embedding == null) return floats;
        for (Double v : embedding) {
            floats.add(v == null ? 0.0f : v.floatValue());
        }
        return floats;
    }

    private static long generatePointId(VectorDoc doc, int fallbackIdx) {
        String content = doc.getContent() == null ? "" : doc.getContent();
        String path = doc.getMetadata() != null ? doc.getMetadata().getOrDefault("file_path", "") : "";
        String key = path + "|" + fallbackIdx + "|" + content.substring(0, Math.min(20, content.length()));
        long hash = 0;
        for (int i = 0; i < key.length(); i++) {
            hash = 31 * hash + key.charAt(i);
        }
        return Math.abs(hash) == 0 ? (long) fallbackIdx + 1 : Math.abs(hash);
    }

    public void close() {
        try {
            client.close();
            log.info("Qdrant 连接已关闭");
        } catch (Exception e) {
            log.warn("关闭 Qdrant 连接失败：{}", e.getMessage());
        }
    }

    // ============ Index / Load / Release ============
    // 说明：Qdrant 在创建 Collection 时自动构建 HNSW 索引，不存在显式 load/release 概念。
    // 下面的方法统一返回 { status: "index_already_exists" | "not_applicable" | "ok" } 语义，
    // 由 AiRagController 直接返回给前端。

    @Override
    public void createIndex(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            throw new RuntimeException("Collection 名称不能为空");
        }
        try {
            boolean exists = collectionExists(collectionName);
            if (exists) {
                log.info("Qdrant 集合 {} 已存在，索引在创建集合时已自动构建，无需单独 createIndex",
                        collectionName);
                throw new IndexAlreadyExistsException(
                        "Qdrant 集合 " + collectionName + " 索引已自动构建，无需重复创建");
            }
            throw new RuntimeException("Qdrant 集合 " + collectionName
                    + " 不存在，不能为不存在的集合创建索引（请先执行知识库同步）");
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Qdrant createIndex 失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void loadCollection(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            throw new RuntimeException("Collection 名称不能为空");
        }
        throw new NotApplicableForVectorStoreException(
                "Qdrant 集合 " + collectionName + "：集合默认常驻内存，不支持显式 load");
    }

    @Override
    public void releaseCollection(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            throw new RuntimeException("Collection 名称不能为空");
        }
        throw new NotApplicableForVectorStoreException(
                "Qdrant 集合 " + collectionName + "：由服务端管理内存，不支持显式 release");
    }

    @Override
    public java.util.List<String> getCollectionNames() {
        try {
            // Qdrant Java SDK 提供 client.listCollectionsAsync()，这里使用反射以便跨版本兼容
            Method listMethod = null;
            for (Method m : client.getClass().getMethods()) {
                if ("listCollectionsAsync".equals(m.getName()) && m.getParameterCount() == 0) {
                    listMethod = m;
                    break;
                }
            }
            if (listMethod == null) {
                log.warn("Qdrant SDK 未找到 listCollectionsAsync 方法，无法读取集合列表");
                return java.util.Collections.emptyList();
            }
            Object future = listMethod.invoke(client);
            Object result = ((java.util.concurrent.Future<?>) future).get(15, TimeUnit.SECONDS);
            return parseQdrantCollectionNames(result);
        } catch (Exception e) {
            log.warn("Qdrant 读取集合列表失败：{}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 尽力解析 listCollectionsAsync 返回值，兼容不同 SDK 版本。
     * - List<String> 直接返回
     * - List<Xxx> 读取每个元素的 getName / name / toString
     * - 其他类型降级为 Collections.emptyList()
     */
    private java.util.List<String> parseQdrantCollectionNames(Object result) {
        if (result == null) return java.util.Collections.emptyList();
        if (result instanceof java.util.List<?>) {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (Object item : (java.util.List<?>) result) {
                if (item == null) continue;
                if (item instanceof String) {
                    names.add((String) item);
                } else {
                    try {
                        Method getName = item.getClass().getMethod("getName");
                        Object nameVal = getName.invoke(item);
                        if (nameVal instanceof String) names.add((String) nameVal);
                        else names.add(item.toString());
                    } catch (NoSuchMethodException ignored1) {
                        try {
                            Method nameGet = item.getClass().getMethod("name");
                            Object nameVal = nameGet.invoke(item);
                            if (nameVal instanceof String) names.add((String) nameVal);
                            else names.add(item.toString());
                        } catch (Exception ignored2) {
                            names.add(item.toString());
                        }
                    } catch (Exception ignored) {
                        names.add(item.toString());
                    }
                }
            }
            return names;
        }
        log.warn("Qdrant listCollectionsAsync 返回类型不支持：{}", result.getClass().getName());
        return java.util.Collections.emptyList();
    }

    @Override
    public void dropCollection(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            throw new RuntimeException("Collection 名称不能为空");
        }
        try {
            // 使用反射调用 client.deleteCollectionAsync(String collectionName)，以兼容跨版本
            Method delMethod = null;
            for (Method m : client.getClass().getMethods()) {
                if ("deleteCollectionAsync".equals(m.getName())
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == String.class) {
                    delMethod = m;
                    break;
                }
            }
            if (delMethod == null) {
                throw new RuntimeException("Qdrant SDK 未找到 deleteCollectionAsync 方法，无法删除集合 "
                        + collectionName);
            }
            Object future = delMethod.invoke(client, collectionName);
            Object raw = ((java.util.concurrent.Future<?>) future).get(15, TimeUnit.SECONDS);
            log.info("Qdrant 集合 {} 已删除（返回值：{}）", collectionName, raw);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Qdrant 删除集合 " + collectionName + " 失败：" + e.getMessage(), e);
        }
    }
}
