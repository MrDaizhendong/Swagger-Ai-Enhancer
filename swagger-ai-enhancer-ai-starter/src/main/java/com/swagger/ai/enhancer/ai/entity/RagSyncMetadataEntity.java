package com.swagger.ai.enhancer.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 增量同步元数据实体，对应表 rag_sync_metadata。
 *
 * 表唯一键 (doc_type, file_path)，用于精确按文件定位元数据。
 */
@Data
@TableName("rag_sync_metadata")
public class RagSyncMetadataEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("doc_type")
    private String docType;

    @TableField("file_path")
    private String filePath;

    @TableField("file_hash")
    private String fileHash;

    @TableField("chunk_count")
    private Integer chunkCount;

    @TableField("last_synced_at")
    private LocalDateTime lastSyncedAt;
}
