package com.swagger.ai.enhancer.ai.rag.pgvector;

import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import com.swagger.ai.enhancer.ai.rag.IndexAlreadyExistsException;
import com.swagger.ai.enhancer.ai.rag.NotApplicableForVectorStoreException;
import com.swagger.ai.enhancer.ai.rag.VectorStoreProvider;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PostgreSQL + pgvector 向量存储实现。
 * 表结构：
 *   id UUID PRIMARY KEY,
 *   content TEXT,
 *   file_path VARCHAR(1024),
 *   chunk_index BIGINT,
 *   embedding vector(N),
 *   (可选：在 embedding 上创建 HNSW / IVFFlat 索引)
 */
@Slf4j
public class PgVectorStore implements VectorStoreProvider {

    private final AiEnhancerProperties properties;

    public PgVectorStore(AiEnhancerProperties properties) {
        this.properties = properties;
        // 确保 pgvector JDBC 类型可用
        try {
            Class.forName("com.pgvector.PGvector");
        } catch (ClassNotFoundException ignored) {
            // 若未打包也可退化，不过构造器仍应正常
        }
    }

    private Connection openConnection() throws SQLException {
        AiEnhancerProperties.PgVectorConfig cfg = properties.getRag().getPgvector();
        String url = String.format("jdbc:postgresql://%s:%d/%s",
                cfg.getHost(), cfg.getPort(), cfg.getDatabase());
        return DriverManager.getConnection(url, cfg.getUsername(), cfg.getPassword());
    }

    @Override
    public void createCollection(String tableName, int dimension) {
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
            // 检查表是否存在
            boolean exists;
            try (ResultSet rs = conn.getMetaData().getTables(null, null, tableName, new String[]{"TABLE"})) {
                exists = rs.next();
            }
            if (exists) {
                log.info("pgvector 表 {} 已存在", tableName);
                return;
            }
            String ddl = String.format(
                    "CREATE TABLE IF NOT EXISTS %s (" +
                            "id UUID PRIMARY KEY, " +
                            "content TEXT, " +
                            "file_path VARCHAR(1024), " +
                            "chunk_index BIGINT, " +
                            "embedding vector(%d)" +
                            ")", tableName, dimension);
            stmt.execute(ddl);
            try {
                stmt.execute(String.format(
                        "CREATE INDEX IF NOT EXISTS %s_embedding_idx ON %s USING hnsw (embedding vector_cosine_ops)",
                        tableName, tableName));
            } catch (SQLException e) {
                // HNSW 需要 pgvector 扩展，可能不可用，降级无索引也可查询
                log.warn("创建 HNSW 索引失败（可降级使用线性扫描）：{}", e.getMessage());
            }
            log.info("pgvector 表 {} 创建成功（维度={}）", tableName, dimension);
        } catch (SQLException e) {
            throw new RuntimeException("创建 pgvector 表失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean collectionExists(String tableName) {
        try (Connection conn = openConnection();
             ResultSet rs = conn.getMetaData().getTables(null, null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        } catch (SQLException e) {
            log.warn("检查 pgvector 表失败：{}", e.getMessage());
            return false;
        }
    }

    @Override
    public void insert(String tableName, List<VectorDoc> docs) {
        if (docs == null || docs.isEmpty()) return;
        if (!collectionExists(tableName)) {
            createCollection(tableName, properties.getRag().getDimension());
        }
        String sql = String.format(
                "INSERT INTO %s (id, content, file_path, chunk_index, embedding) VALUES (?, ?, ?, ?, ?::vector)",
                tableName);
        try (Connection conn = openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            int batchSize = 0;
            for (VectorDoc doc : docs) {
                int idx = 1;
                UUID id;
                try {
                    id = (doc.getId() != null && !doc.getId().isBlank())
                            ? UUID.fromString(doc.getId())
                            : UUID.nameUUIDFromBytes(
                            ((doc.getContent() == null ? "" : doc.getContent()) +
                                    (doc.getMetadata() != null ? doc.getMetadata().getOrDefault("file_path", "") : "") +
                                    (doc.getMetadata() != null ? doc.getMetadata().getOrDefault("chunk_index", "0") : "0")
                            ).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    id = UUID.randomUUID();
                }
                pstmt.setObject(idx++, id);
                pstmt.setString(idx++, doc.getContent() == null ? "" : doc.getContent());
                String filePath = "";
                long chunkIdx = 0L;
                if (doc.getMetadata() != null) {
                    filePath = doc.getMetadata().getOrDefault("file_path", "");
                    String ci = doc.getMetadata().get("chunk_index");
                    try {
                        chunkIdx = ci == null ? 0L : Long.parseLong(ci);
                    } catch (NumberFormatException ignored) {
                        // 保持 0
                    }
                }
                pstmt.setString(idx++, filePath);
                pstmt.setLong(idx++, chunkIdx);
                pstmt.setObject(idx++, toPgVectorString(doc.getEmbedding()));
                pstmt.addBatch();
                batchSize++;
                if (batchSize >= 100) {
                    pstmt.executeBatch();
                    batchSize = 0;
                }
            }
            if (batchSize > 0) {
                pstmt.executeBatch();
            }
            log.info("pgvector 已插入 {} 条到表 {}", docs.size(), tableName);
        } catch (SQLException e) {
            throw new RuntimeException("插入 pgvector 失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<SearchResult> search(String tableName, List<Double> queryVector,
                                     int topK, double minSimilarity) {
        if (!collectionExists(tableName) || queryVector == null || queryVector.isEmpty()) {
            return Collections.emptyList();
        }
        // 1 - cosine_distance 作为 cosine 相似度
        String sql = String.format(
                "SELECT id, content, file_path, (1 - (embedding <=> ?::vector)) AS similarity " +
                        "FROM %s ORDER BY embedding <=> ?::vector LIMIT ?", tableName);
        List<SearchResult> results = new ArrayList<>();
        try (Connection conn = openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            String vec = toPgVectorString(queryVector);
            pstmt.setString(1, vec);
            pstmt.setString(2, vec);
            pstmt.setInt(3, topK);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    double similarity = rs.getDouble("similarity");
                    if (similarity < minSimilarity) continue;
                    Map<String, String> metadata = new HashMap<>();
                    String fp = rs.getString("file_path");
                    if (fp != null) metadata.put("file_path", fp);
                    SearchResult sr = SearchResult.builder()
                            .id(rs.getString("id"))
                            .content(rs.getString("content") == null ? "" : rs.getString("content"))
                            .score(similarity)
                            .metadata(metadata)
                            .build();
                    results.add(sr);
                }
            }
            log.info("pgvector 检索完成，命中 {} 条（阈值 {}）", results.size(), minSimilarity);
            return results;
        } catch (SQLException e) {
            throw new RuntimeException("pgvector 搜索失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteByFile(String tableName, String filePath) {
        if (!collectionExists(tableName) || filePath == null || filePath.isBlank()) return;
        String sql = String.format("DELETE FROM %s WHERE file_path = ?", tableName);
        try (Connection conn = openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, filePath);
            int n = pstmt.executeUpdate();
            log.info("pgvector 已删除表 {} 中 {} 行（file_path={}）", tableName, n, filePath);
        } catch (SQLException e) {
            throw new RuntimeException("pgvector 删除失败：" + e.getMessage(), e);
        }
    }

    private static String toPgVectorString(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding.get(i) == null ? "0.0" : embedding.get(i).toString());
        }
        sb.append(']');
        return sb.toString();
    }

    public void close() {
        // JDBC 采用每次请求创建/关闭，无需全局状态
    }

    // ============ Index / Load / Release ============
    // 说明：pgvector 在建表时自动创建 HNSW/IVFFlat 索引，PostgreSQL 数据由 shared_buffers 管理，
    // 不存在显式 load/release 概念。下面的方法统一返回 { status: "index_already_exists" | "not_applicable" | "ok" } 语义。

    @Override
    public void createIndex(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            throw new RuntimeException("Collection 名称不能为空");
        }
        try {
            if (!collectionExists(collectionName)) {
                throw new RuntimeException("pgvector 表 " + collectionName
                        + " 不存在，不能为不存在的表创建索引（请先执行知识库同步）");
            }
            log.info("pgvector 表 {}：索引在建表时已自动构建，无需单独 createIndex", collectionName);
            throw new IndexAlreadyExistsException(
                    "pgvector 表 " + collectionName + " 索引已自动构建，无需重复创建");
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("pgvector createIndex 失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void loadCollection(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            throw new RuntimeException("Collection 名称不能为空");
        }
        throw new NotApplicableForVectorStoreException(
                "pgvector 表 " + collectionName + "：PostgreSQL 通过 shared_buffers 管理缓存，不支持显式 load");
    }

    @Override
    public void releaseCollection(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            throw new RuntimeException("Collection 名称不能为空");
        }
        throw new NotApplicableForVectorStoreException(
                "pgvector 表 " + collectionName + "：PostgreSQL 通过 shared_buffers 管理缓存，不支持显式 release");
    }

    @Override
    public java.util.List<String> getCollectionNames() {
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema = 'public' "
                             + "AND (table_name LIKE 'swagger_knowledge_%' "
                             + "     OR table_name LIKE 'rag_%' "
                             + "     OR table_name IN ("
                             + "       SELECT tablename FROM pg_tables WHERE schemaname='public' "
                             + "       AND tablename IN "
                             + "       (SELECT table_name FROM information_schema.columns "
                             + "        WHERE table_schema='public' AND data_type='vector'))"
                             + ") ORDER BY table_name")) {
            java.util.List<String> names = new java.util.ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            return names;
        } catch (Exception e) {
            log.warn("pgvector 读取表名列表失败：{}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public void dropCollection(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            throw new RuntimeException("Collection 名称不能为空");
        }
        // 做一次简单的白名单校验：collectionName 只能包含字母、数字、下划线
        if (!collectionName.matches("[A-Za-z0-9_]+")) {
            throw new RuntimeException("Collection 名称非法，仅允许字母、数字、下划线");
        }
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + collectionName);
            log.info("pgvector 表 {} 已删除", collectionName);
        } catch (SQLException e) {
            throw new RuntimeException("pgvector 删除表 " + collectionName + " 失败：" + e.getMessage(), e);
        }
    }
}
