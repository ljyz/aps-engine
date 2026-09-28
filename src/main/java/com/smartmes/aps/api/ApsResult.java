package com.smartmes.aps.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * APS 排产结果，不包含数据库实体和持久化行为。
 *
 * @param schemaVersion 输出协议版本
 * @param requestId 原排产请求 ID
 * @param status 本次排产最终状态
 * @param engineVersion 引擎版本
 * @param solverType 实际使用的求解算法
 * @param algorithmId 实际使用的算法标识
 * @param algorithmVersion 实际使用的算法版本
 * @param randomSeed 原请求随机种子
 * @param startedAt 计算开始时间
 * @param completedAt 计算结束时间
 * @param elapsedMillis 计算耗时毫秒数
 * @param bestSolution 最优方案；没有任何可排任务时可能为空
 * @param candidateSolutionList 根据输出选项返回的不同实际排法，按评分及同分累计完成耗时排序，推荐在第一项
 * @param issueList 校验错误、运行警告和不可排原因
 * @param inputDigest 根据输出选项返回的输入数量摘要
 * @param statistics 本次排产统计
 */
public record ApsResult(
        String schemaVersion,
        String requestId,
        Status status,
        String engineVersion,
        ApsRequest.SolverType solverType,
        String algorithmId,
        String algorithmVersion,
        long randomSeed,
        Instant startedAt,
        Instant completedAt,
        long elapsedMillis,
        Solution bestSolution,
        List<Solution> candidateSolutionList,
        List<Issue> issueList,
        InputDigest inputDigest,
        Statistics statistics
) {
    /** 排产状态。 */
    public enum Status { SUCCESS, INFEASIBLE, INVALID_INPUT, TIMEOUT, CANCELLED, INTERNAL_ERROR }

    /** 问题严重程度；ERROR 表示请求不能正常排产。 */
    public enum Severity { INFO, WARNING, ERROR }

    /**
     * 一个排产方案。
     *
     * @param solutionId 方案 ID
     * @param feasible 是否所有任务都得到可行安排
     * @param score 0至100综合评分，值越大越优；搜索保留12位小数
     * @param planStartTime 方案内最早开始时间
     * @param planEndTime 方案内最晚结束时间
     * @param cardList 拆批后生成的流转卡；字段名对齐 ncloud 的 ScheduleFlowCard
     * @param itemList 排产明细；一条记录对应一个拆分批次的一道工序，字段名对齐 ncloud 的 SchedulePlan.itemList
     * @param taskList 按拆分批次分组的算法诊断结果，不用于 ncloud 排产方案落库
     */
    public record Solution(
            String solutionId,
            boolean feasible,
            BigDecimal score,
            LocalDateTime planStartTime,
            LocalDateTime planEndTime,
            List<ScheduleFlowCard> cardList,
            List<SchedulePlanItem> itemList,
            List<TaskResult> taskList
    ) {}

    /**
     * 拆批后生成的流转卡模板。JAR 不依赖 ncloud 的 ID 和编码生成器，因此调用方落库前应替换
     * {@code id/cardCode}，并将同一逻辑卡片的新 ID 回填到明细的 {@code cardId}。
     */
    public record ScheduleFlowCard(
            String id,
            String planId,
            String cardCode,
            BigDecimal planNum,
            String planStatus,
            String workshopId,
            String workshopPlanId
    ) {}

    /**
     * ncloud 人工排产方案明细兼容模型。
     * <p>
     * 字段名与 {@code com.ncloud.mes.model.SchedulePlanItem} 的工单生成核心字段保持一致。
     * ID 在引擎协议中使用 String，以保持 JAR 可供多个项目使用；ncloud 接入层负责转换为 Long，
     * 并使用 TinyId、业务编码规则生成正式 {@code id/orderCode/cardId}。
     */
    public record SchedulePlanItem(
            String id,
            String planId,
            String orderCode,
            String workshopPlanId,
            String cardId,
            String workOrderId,
            String stepId,
            String deviceId,
            String teamId,
            LocalDateTime planStartTime,
            LocalDateTime planEndTime,
            Integer prepareCost,
            Integer processCost,
            BigDecimal standardNum,
            BigDecimal planNum,
            String planStatus,
            Integer sortOrder,
            boolean urgent,
            String execStatus,
            String itemRemark,
            String stepCode,
            String stepName,
            String stepMark,
            String stepDesc,
            Boolean lastStep,
            String deviceCode,
            String deviceName,
            String teamCode,
            String teamName,
            String productSkuCode,
            String productSkuDesc,
            String productSpuCode,
            String productSpuName,
            String productSpuModel,
            String productSpuSpec,
            String spuImg,
            String spuUnit,
            String spuAttr,
            String workshopPlanCode,
            String workshopCode,
            String workshopName,
            String cardCode
    ) {}

    /**
     * 单个工单的排产结果。
     *
     * @param taskId 来源待排产计划 ID
     * @param splitTaskId 算法生成的拆分任务 ID，调用方可据此创建正式工单
     * @param splitNo 当前拆分序号，从 1 开始
     * @param splitCount 当前计划拆分出的工单总数
     * @param planNum 当前拆分工单的生产数量
     * @param orderCode 原计划号或来源单号
     * @param requiredStartTime 原工单允许开始时间
     * @param requiredEndTime 原工单要求完成时间
     * @param planStartTime 实际计划开始时间
     * @param planEndTime 实际计划结束时间
     * @param onTime 是否按期完成
     * @param latenessMinutes 延期分钟数，按期完成时为 0
     * @param stepList 按工艺拓扑顺序输出的工序结果
     */
    public record TaskResult(
            String taskId,
            String splitTaskId,
            int splitNo,
            int splitCount,
            BigDecimal planNum,
            String orderCode,
            LocalDateTime requiredStartTime,
            LocalDateTime requiredEndTime,
            LocalDateTime planStartTime,
            LocalDateTime planEndTime,
            boolean onTime,
            long latenessMinutes,
            List<StepResult> stepList
    ) {}

    /**
     * 单道工序的排产结果。
     *
     * @param splitTaskId 所属拆分工单 ID
     * @param stepId 原工序 ID
     * @param stepCode 原工序编码
     * @param stepName 原工序名称
     * @param planStartTime 工序第一个工作段的开始时间
     * @param planEndTime 工序最后一个工作段的结束时间
     * @param prepareCost 实际采用的准备工时秒数
     * @param processCost 实际采用的加工工时秒数
     * @param defaultDurationApplied 是否触发缺少工时时默认 8 小时规则
     * @param resourceList 实际选择并占用的资源
     * @param workSegmentList 实际工作时间段；多个时间段之间不计入有效工时
     */
    public record StepResult(
            String splitTaskId,
            String stepId,
            String stepCode,
            String stepName,
            LocalDateTime planStartTime,
            LocalDateTime planEndTime,
            int prepareCost,
            int processCost,
            boolean defaultDurationApplied,
            List<ResourceAssignment> resourceList,
            List<TimeSegment> workSegmentList
    ) {}

    /**
     * 工序实际选择的资源。
     *
     * @param resourceType 资源类型
     * @param resourceId 资源 ID
     * @param resourceCode 资源编码
     * @param resourceName 资源名称
     * @param capacityUsed 本工序占用容量
     */
    public record ResourceAssignment(
            ApsRequest.ResourceType resourceType,
            String resourceId,
            String resourceCode,
            String resourceName,
            BigDecimal capacityUsed
    ) {}

    /**
     * 一段连续的有效工作时间。
     *
     * @param startTime 开始时间
     * @param endTime 结束时间
     */
    public record TimeSegment(LocalDateTime startTime, LocalDateTime endTime) {}

    /** 可选的输入快照摘要，用于确认本次计算实际接收的数据规模。 */
    public record InputDigest(
            int taskCount,
            int resourceCount,
            int shiftDayCount,
            int occupancyCount,
            int stepCount
    ) {}

    /**
     * 输入或排产过程中的问题。
     *
     * @param severity 严重程度
     * @param code 稳定的问题代码，调用方应优先根据代码处理
     * @param message 中文说明
     * @param objectType 关联对象类型
     * @param objectId 关联对象 ID
     * @param details 结构化补充信息
     */
    public record Issue(
            Severity severity,
            String code,
            String message,
            String objectType,
            String objectId,
            Map<String, Object> details
    ) {}

    /**
     * 排产汇总统计。
     *
     * @param sourceTaskCount 输入待排产计划总数
     * @param splitTaskCount 按最大制单数量拆分后的工单总数
     * @param scheduledSplitTaskCount 已排成功的拆分工单数
     * @param unscheduledSplitTaskCount 未排成功的拆分工单数
     * @param stepCount 输入路线展开后的工序总数
     * @param scheduledStepCount 已排成功工序数
     * @param defaultDurationStepCount 触发默认 8 小时的工序数
     * @param onTimeTaskCount 按期完成工单数
     * @param lateTaskCount 延期工单数
     */
    public record Statistics(
            int sourceTaskCount,
            int splitTaskCount,
            int scheduledSplitTaskCount,
            int unscheduledSplitTaskCount,
            int stepCount,
            int scheduledStepCount,
            int defaultDurationStepCount,
            int onTimeTaskCount,
            int lateTaskCount
    ) {}
}
