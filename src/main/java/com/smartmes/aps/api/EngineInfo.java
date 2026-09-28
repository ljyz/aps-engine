package com.smartmes.aps.api;

import java.util.List;
import java.util.Set;

/**
 * 当前 JAR 的能力说明。
 *
 * @param engineVersion 引擎实现版本
 * @param supportedSchemaVersions 支持的输入输出协议版本
 * @param algorithmList 当前版本内置的算法列表
 * @param supportedDirectionList 支持的正排、倒排方向
 */
public record EngineInfo(
        String engineVersion,
        Set<String> supportedSchemaVersions,
        List<AlgorithmDescriptor> algorithmList,
        Set<String> supportedDirectionList
) {
    /**
     * 单个算法的能力描述，调用方可根据这些字段生成算法选择项并校验配置。
     *
     * @param algorithmId 稳定的算法标识，作为排产请求的 algorithmId
     * @param algorithmName 算法中文名称
     * @param algorithmVersion 算法实现版本
     * @param description 算法用途和能力摘要
     * @param supportedResourceTypeList 支持的资源类型
     * @param supportedConstraintList 支持的约束代码列表
     * @param deterministic 相同输入是否产生相同结果
     * @param supportsPartialSolution 是否支持返回部分可行方案
     * @param supportsProgress 是否支持进度回调
     * @param supportsCancellation 是否支持主动取消
     */
    public record AlgorithmDescriptor(
            String algorithmId,
            String algorithmName,
            String algorithmVersion,
            String description,
            Set<ApsRequest.ResourceType> supportedResourceTypeList,
            Set<String> supportedConstraintList,
            boolean deterministic,
            boolean supportsPartialSolution,
            boolean supportsProgress,
            boolean supportsCancellation
    ) {}
}
