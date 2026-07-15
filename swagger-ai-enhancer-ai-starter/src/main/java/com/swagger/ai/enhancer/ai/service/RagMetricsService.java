package com.swagger.ai.enhancer.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swagger.ai.enhancer.ai.entity.RagSyncMetadataEntity;
import com.swagger.ai.enhancer.ai.mapper.RagSyncMetadataMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAG 检索指标服务：跨请求积累检索统计数据（命中率、平均分、最高分数、分层分布等），
 * 并提供 rag_sync_metadata 表同步状态的汇总查询能力。
 *
 * 设计：
 *   - 检索统计存储在内存中（ConcurrentHashMap，key = docType），
 *     重启应用后清零（这是预期行为，以便与新启动的向量库检索状态对齐）。
 *   - 同步统计通过 RagSyncMetadataMapper 实时聚合（totalFiles、totalChunks、lastSyncTime）。
 *   - 该 Bean 独立于 AiController / AiRagController，避免循环依赖。
 */
@Slf4j
public class RagMetricsService {

    /** 检索统计缓存（key = docType）。 */
    private final Map<String, RetrievalStats> retrievalStatsMap = new ConcurrentHashMap<>();

    /** 可选：若应用未配置 MyBatis-Plus（即 rag_sync_metadata 表不可用），则降级为 null。 */
    private final RagSyncMetadataMapper metadataMapper;

    public RagMetricsService() {
        this(null);
    }

    public RagMetricsService(RagSyncMetadataMapper metadataMapper) {
        this.metadataMapper = metadataMapper;
    }

    // ==================== 检索统计 ====================

    /**
     * 记录一次检索调用。
     *
     * @param docType      文档类型（collection 分组）
     * @param hitHigh      是否命中高相关片段（score >= 0.7 有任意一条）
     * @param highestScore 本次检索的最高相似度分；没有命中或过滤后为空传 0.0
     * @param highCount    高相关片段数（score >= 0.7）
     * @param mediumCount  中相关片段数（0.4 <= score < 0.7）
     * @param lowCount     低相关 / 被过滤片段数（score < 0.4 或向量库直接返回的低相关项）
     */
    public void recordRetrieval(String docType,
                                boolean hitHigh,
                                double highestScore,
                                int highCount,
                                int mediumCount,
                                int lowCount) {
        if (docType == null || docType.isBlank()) {
            return;
        }
        RetrievalStats stats = retrievalStatsMap.computeIfAbsent(docType, k -> new RetrievalStats());
        stats.record(hitHigh, highestScore, highCount, mediumCount, lowCount);
        log.debug("[RAG] 记录检索指标：docType={}, hitHigh={}, highestScore={}",
                docType, hitHigh, highestScore);
    }

    /**
     * 获取指定 docType 的检索统计；若从未记录过则返回全为 0 的空对象。
     */
    public RetrievalStats getRetrievalStats(String docType) {
        if (docType == null) {
            return new RetrievalStats();
        }
        RetrievalStats existing = retrievalStatsMap.get(docType);
        return existing != null ? existing : new RetrievalStats();
    }

    /**
     * 清空某个 docType 的检索统计；传 null 则清空全部。
     */
    public void resetRetrievalStats(String docType) {
        if (docType == null || docType.isBlank()) {
            retrievalStatsMap.clear();
        } else {
            retrievalStatsMap.remove(docType);
        }
    }

    // ==================== 同步统计 ====================

    /**
     * 返回指定 docType 的同步元数据汇总；若 mapper 未注入或查询失败，返回 null。
     */
    public SyncMetadataSummary getSyncMetadataSummary(String docType) {
        if (metadataMapper == null) {
            return null;
        }
        if (docType == null || docType.isBlank()) {
            return null;
        }
        try {
            LambdaQueryWrapper<RagSyncMetadataEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(RagSyncMetadataEntity::getDocType, docType);
            List<RagSyncMetadataEntity> rows = metadataMapper.selectList(qw);
            if (rows == null || rows.isEmpty()) {
                return SyncMetadataSummary.builder()
                        .docType(docType)
                        .totalFiles(0)
                        .totalChunks(0)
                        .lastSyncTime(null)
                        .build();
            }
            int totalFiles = rows.size();
            int totalChunks = 0;
            LocalDateTime lastSyncTime = null;
            for (RagSyncMetadataEntity e : rows) {
                if (e.getChunkCount() != null) {
                    totalChunks += e.getChunkCount();
                }
                if (e.getLastSyncedAt() != null
                        && (lastSyncTime == null || e.getLastSyncedAt().isAfter(lastSyncTime))) {
                    lastSyncTime = e.getLastSyncedAt();
                }
            }
            return SyncMetadataSummary.builder()
                    .docType(docType)
                    .totalFiles(totalFiles)
                    .totalChunks(totalChunks)
                    .lastSyncTime(lastSyncTime)
                    .build();
        } catch (Exception ex) {
            log.warn("[RAG] 查询 rag_sync_metadata 失败（docType={}）：{}", docType, ex.getMessage());
            return null;
        }
    }

    // ==================== 内部数据结构 ====================

    /**
     * 单 docType 的检索统计。字段使用 volatile / synchronized 保证并发可见性与原子更新。
     */
    public static class RetrievalStats {
        private volatile int totalRetrievals = 0;
        private volatile int hitRetrievals = 0;
        private double totalTopScore = 0.0;
        private volatile double maxTopScore = 0.0;
        private volatile int highCount = 0;
        private volatile int mediumCount = 0;
        private volatile int lowCount = 0;

        /** 记录一次检索结果。加锁保证同一 docType 的累加是线程安全的。 */
        public synchronized void record(boolean hitHigh, double highestScore,
                                        int highCountInc, int mediumCountInc, int lowCountInc) {
            totalRetrievals++;
            if (hitHigh) {
                hitRetrievals++;
            }
            if (highestScore > 0) {
                totalTopScore += highestScore;
                if (highestScore > maxTopScore) {
                    maxTopScore = highestScore;
                }
            }
            highCount += Math.max(0, highCountInc);
            mediumCount += Math.max(0, mediumCountInc);
            lowCount += Math.max(0, lowCountInc);
        }

        public int getTotalRetrievals() { return totalRetrievals; }
        public int getHitRetrievals() { return hitRetrievals; }
        public synchronized double getTotalTopScore() { return totalTopScore; }
        public double getMaxTopScore() { return maxTopScore; }
        public int getHighCount() { return highCount; }
        public int getMediumCount() { return mediumCount; }
        public int getLowCount() { return lowCount; }

        /** 命中率 = hitRetrievals / totalRetrievals。无检索时返回 0.0。 */
        public double getHitRate() {
            int total = totalRetrievals;
            if (total <= 0) return 0.0;
            return (double) hitRetrievals / total;
        }

        /** 命中时的最高相似度分平均值。无命中时返回 0.0。 */
        public synchronized double getAvgTopScore() {
            int hits = hitRetrievals;
            if (hits <= 0) return 0.0;
            return totalTopScore / hits;
        }
    }

    /** rag_sync_metadata 表汇总结构（DTO）。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncMetadataSummary {
        private String docType;
        /** 已同步的文件总数（即不同 filePath 的行数）。 */
        private int totalFiles;
        /** 片段总数累加值。 */
        private int totalChunks;
        /** 最近一次同步时间（该 docType 内所有文件的 lastSyncedAt 最大值）。 */
        private LocalDateTime lastSyncTime;
    }
}
