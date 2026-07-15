package com.swagger.ai.enhancer.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.swagger.ai.enhancer.ai.entity.RagSyncMetadataEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * rag_sync_metadata 表 CRUD Mapper。
 *
 * 通过 MyBatis-Plus QueryWrapper 可实现按 (doc_type, file_path) 查询、删除等逻辑。
 */
@Mapper
public interface RagSyncMetadataMapper extends BaseMapper<RagSyncMetadataEntity> {
}
