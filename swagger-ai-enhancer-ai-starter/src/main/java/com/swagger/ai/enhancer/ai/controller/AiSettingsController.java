package com.swagger.ai.enhancer.ai.controller;

import com.swagger.ai.enhancer.ai.dto.RagConfigDto;
import com.swagger.ai.enhancer.ai.service.RagConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 设置 REST 控制器（按文档类型独立配置）。
 *
 * 端点：
 *   GET  /api/ai/settings              返回全部 6 个标准文档类型的配置（Map：docType → RagConfigDto）
 *   GET  /api/ai/settings/{docType}   返回指定文档类型的配置
 *   PUT  /api/ai/settings/{docType}   更新指定文档类型的配置
 *
 * 敏感字段（password / API Key）在 YAML/环境变量管理。本控制器只暴露无敏感字段。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/settings")
public class AiSettingsController {

    private final RagConfigService service;

    @Autowired
    public AiSettingsController(RagConfigService service) {
        this.service = service;
    }

    /** 列出全部 6 种文档类型的配置；若数据库某行缺失，返回 YAML 默认值 */
    @GetMapping
    public Map<String, RagConfigDto> getAllSettings() {
        Map<String, RagConfigDto> map = new LinkedHashMap<>();
        Map<String, RagConfigDto> dbConfigs = safeLoadAll();
        for (String docType : RagConfigService.STANDARD_DOC_TYPES) {
            if (dbConfigs != null && dbConfigs.containsKey(docType)) {
                map.put(docType, dbConfigs.get(docType));
            } else {
                map.put(docType, service.getConfigOrDefault(docType));
            }
        }
        return map;
    }

    /** 读取指定文档类型的配置 */
    @GetMapping("/{docType}")
    public RagConfigDto getSettingByDocType(@PathVariable("docType") String docType) {
        if (docType == null || docType.isBlank()) {
            throw new IllegalArgumentException("docType 不能为空");
        }
        return service.getConfigOrDefault(docType);
    }

    /** 保存指定文档类型的配置 */
    @PutMapping("/{docType}")
    public Map<String, Object> saveSetting(@PathVariable("docType") String docType,
                                            @RequestBody RagConfigDto dto) {
        if (!StringUtils.hasText(docType)) {
            return errorResponse("docType 不能为空");
        }
        if (dto == null) {
            return errorResponse("请求体不能为空");
        }
        try {
            service.saveConfig(docType, dto);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", "ok");
            r.put("docType", docType);
            return r;
        } catch (Exception e) {
            log.warn("[rag-config] 保存配置失败 (docType={}): {}", docType, e.getMessage());
            return errorResponse("保存失败: " + e.getMessage());
        }
    }

    // ============ 工具方法 ============

    private Map<String, RagConfigDto> safeLoadAll() {
        try {
            return service.loadAllConfigs();
        } catch (Exception e) {
            log.warn("[rag-config] 读取数据库失败: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "error");
        r.put("message", message);
        return r;
    }

    // 保留此方法以便后续扩展（list 列表端点）
    static List<String> standardDocTypes() {
        return RagConfigService.STANDARD_DOC_TYPES;
    }
}
