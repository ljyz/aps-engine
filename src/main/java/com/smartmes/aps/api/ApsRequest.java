package com.smartmes.aps.api;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * APS 引擎使用的单次排产输入快照。
 * <p>
 * DTO 全部采用不可变 record，调用方应在调用前一次性准备工单、工艺路线、班次、
 * 资源及已有占用。引擎不会根据 ID 查询数据库，也不会修改传入集合。
 *
 * @param schemaVersion 协议版本，第一版固定为 {@code 1.0}
 * @param requestId 本次请求唯一标识
 * @param solverType 求解算法，支持启发式和遗传算法
 * @param algorithmId 排程方向，支持 {@code FORWARD} 和 {@code BACKWARD}
 * @param currentTime 业务当前本地时间，正排不得早于该时间
 * @param randomSeed 随机种子；用于并列候选资源的稳定选择，相同输入和种子可复现
 * @param algorithmParam 算法参数，为空时使用默认参数
 * @param shiftScheduleList 按日期展开的上班班次；为空时按 7×24 小时工作
 * @param resourceList 资源及资源占用快照；没有资源约束时允许为空
 * @param taskList 待排产计划；算法按最大制单数量拆分工单并展开各自工艺路线
 * @param outputParam 输出控制参数，为空时使用默认参数
 */
public record ApsRequest(
        String schemaVersion,
        String requestId,
        SolverType solverType,
        String algorithmId,
        LocalDateTime currentTime,
        long randomSeed,
        AlgorithmParam algorithmParam,
        List<ShiftSchedule> shiftScheduleList,
        List<Resource> resourceList,
        List<Task> taskList,
        OutputParam outputParam
) {
    /** 求解算法与正排、倒排方向相互独立。 */
    public enum SolverType { HEURISTIC, GENETIC }

    /** 可参与有限产能计算的资源类型。 */
    public enum ResourceType { DEVICE, TEAM, TOOLING, OTHER }

    /**
     * 算法参数。
     *
     * @param useIdleTime 是否允许设备利用同一天已排班班次之间的空档；不扩展班组工作时间
     * @param appendMode 是否避让已有外部占用并在其基础上排产
     * @param allowLateCompletion BACKWARD 严格倒排失败后是否允许对失败工单延期补排；FORWARD 始终允许超期
     * @param lateCompletionLimit BACKWARD 允许延期补排时，单个工单最多可超过要求结束时间多久
     * @param enableToolingConstraint 是否启用工装、夹具等 TOOLING 资源约束
     * @param minProcessIntervalMinutes 工序边未指定最小间隔时使用的全局分钟数
     * @param maxProcessIntervalHours 工序边未单独指定时的全局最大间隔小时数，0表示不限制
     * @param finishTimeWeight 固定请求基准到最终完成时间的评分权重，0禁用
     * @param achievementRateWeight 完整排入拆分工单占全部待排工单比例的评分权重，0禁用
     * @param onTimeDeliveryWeight 准交拆分工单占全部待排工单比例的评分权重，0禁用；三个权重全0时按等权处理
     * @param maxParallelism 并行评估资源组合的最大工作线程数
     * @param timeout 单次排产超时时间
     * @param globalTimeScale 全局加工工时倍率，空值或非正数按 1 处理
     * @param heuristicParam 启发式算法专用参数
     * @param geneticParam 遗传算法专用参数
     */
    public record AlgorithmParam(
            boolean useIdleTime,
            boolean appendMode,
            boolean allowLateCompletion,
            Duration lateCompletionLimit,
            boolean enableToolingConstraint,
            int minProcessIntervalMinutes,
            int maxProcessIntervalHours,
            int finishTimeWeight,
            int achievementRateWeight,
            int onTimeDeliveryWeight,
            int maxParallelism,
            Duration timeout,
            BigDecimal globalTimeScale,
            HeuristicParam heuristicParam,
            GeneticParam geneticParam
    ) {
        /** @return 第一版推荐的默认算法参数 */
        public static AlgorithmParam defaults() {
            return new AlgorithmParam(
                    true, true, false, Duration.ofDays(30), false,
                    0, 0, 1, 1, 1,
                    Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 16)),
                    Duration.ofMinutes(10), BigDecimal.ONE,
                    HeuristicParam.defaults(), GeneticParam.defaults());
        }
    }

    /**
     * 启发式有限产能排程专用参数。
     *
     * @param candidateSearchCount 独立搜索次数；启用顺序扰动后每次使用不同但可复现的候选顺序
     * @param maxResourceCombinationsPerStep 单道工序最多评估的设备、班组等资源组合数
     * @param enableCandidateOrderPerturbation 是否按随机种子扰动同优先级工单及同优先级资源顺序
     */
    public record HeuristicParam(
            int candidateSearchCount,
            int maxResourceCombinationsPerStep,
            boolean enableCandidateOrderPerturbation
    ) {
        public static HeuristicParam defaults() {
            return new HeuristicParam(1, 256, true);
        }
    }

    /**
     * 遗传算法专用参数。遗传算法只搜索工单顺序和资源偏好，所有染色体仍通过同一个
     * 有限产能解码器生成可执行方案，因此不会绕过工艺、班次和资源容量硬约束。
     *
     * @param populationSize 每代染色体数量
     * @param maxGenerations 最大进化代数
     * @param crossoverRate 染色体交叉概率，范围 0~1
     * @param mutationRate 染色体变异概率，范围 0~1
     * @param eliteCount 每代直接保留的最优染色体数量
     * @param tournamentSize 锦标赛选择每次抽取的染色体数量
     * @param stagnationGenerations 综合分及同分累计完成耗时连续多少代未改善时提前停止，0 表示禁用
     * @param heuristicSeedRate 初始种群中采用启发式顺序的比例，范围 0~1
     */
    public record GeneticParam(
            int populationSize,
            int maxGenerations,
            BigDecimal crossoverRate,
            BigDecimal mutationRate,
            int eliteCount,
            int tournamentSize,
            int stagnationGenerations,
            BigDecimal heuristicSeedRate
    ) {
        public static GeneticParam defaults() {
            return new GeneticParam(30, 50, new BigDecimal("0.80"), new BigDecimal("0.10"),
                    2, 3, 15, new BigDecimal("0.30"));
        }
    }

    /**
     * 输出控制参数。第一版始终返回一个最优方案，部分字段为后续多候选版本预留。
     *
     * @param candidateLimit 去重后最多返回的候选方案数，实际数量可能小于上限
     * @param includeCandidates 是否返回候选方案
     * @param includeResourceTimeline 是否返回资源时间轴
     * @param includeDiagnostics 是否返回诊断信息
     * @param includeInputDigest 是否返回输入摘要
     */
    public record OutputParam(
            int candidateLimit,
            boolean includeCandidates,
            boolean includeResourceTimeline,
            boolean includeDiagnostics,
            boolean includeInputDigest
    ) {
        /** @return 第一版默认输出参数 */
        public static OutputParam defaults() {
            return new OutputParam(1, false, true, true, true);
        }
    }

    /**
     * 某个自然日实际生效的班次。
     *
     * @param scheduleDate 排班日期；顶层整体为空时才表示 7×24 小时
     * @param shiftList 当日上班班次；日期存在但列表为空表示当天不上班
     */
    public record ShiftSchedule(LocalDate scheduleDate, List<Shift> shiftList) {}

    /**
     * 班次定义，字段名与 ncloud 班次模型保持一致。
     *
     * @param shiftId 班次 ID
     * @param shiftCode 班次编码
     * @param shiftName 班次名称
     * @param startTime 当地开始时间
     * @param endTime 当地结束时间
     * @param crossDay 结束时间是否属于次日
     * @param standardTime 标准工作分钟数，作为班次业务快照保留
     * @param teamIdList 该班次上班的班组 ID
     * @param deviceIdList 该班次上班的设备 ID
     */
    public record Shift(
            String shiftId,
            String shiftCode,
            String shiftName,
            LocalTime startTime,
            LocalTime endTime,
            boolean crossDay,
            Integer standardTime,
            List<String> teamIdList,
            List<String> deviceIdList
    ) {
        /** 兼容未传设备排班范围的旧调用方。 */
        public Shift(String shiftId, String shiftCode, String shiftName, LocalTime startTime,
                     LocalTime endTime, boolean crossDay, Integer standardTime,
                     List<String> teamIdList) {
            this(shiftId, shiftCode, shiftName, startTime, endTime, crossDay,
                    standardTime, teamIdList, null);
        }
    }

    /**
     * 有限产能资源快照。
     *
     * @param resourceType 资源类型
     * @param resourceId 资源 ID；与类型共同组成唯一键
     * @param resourceCode 资源编码
     * @param resourceName 资源名称
     * @param capacity 同时可用容量，空值或非正数按 1 处理
     * @param occupancyList 排产前已经存在的占用时段，不是可用时段
     * @param attributes 调用方扩展属性，第一版不解释
     */
    public record Resource(
            ResourceType resourceType,
            String resourceId,
            String resourceCode,
            String resourceName,
            BigDecimal capacity,
            List<ResourceOccupancy> occupancyList,
            Map<String, String> attributes
    ) {}

    /**
     * 资源已有占用时段。
     *
     * @param occupancyId 占用记录 ID
     * @param startTime 占用开始时间
     * @param endTime 占用结束时间
     * @param capacityUsed 已占用容量，空值或非正数按 1 处理
     * @param sourceType 来源类型，如已排工单、锁定计划或停机
     * @param sourceId 来源业务 ID
     * @param remark 备注
     */
    public record ResourceOccupancy(
            String occupancyId,
            LocalDateTime startTime,
            LocalDateTime endTime,
            BigDecimal capacityUsed,
            String sourceType,
            String sourceId,
            String remark
    ) {}

    /**
     * 待排产计划。算法根据 maxOrderQuantity 将计划数量拆成多个工单，
     * 每个拆分工单再按内嵌 route 展开完整工序。
     *
     * @param taskId 待排产计划唯一 ID，也是拆分结果的来源计划 ID
     * @param parentTaskId 父计划 ID，同时建立父子任务先后约束
     * @param orderCode 计划号或来源单号
     * @param saleOrderCode 销售订单号
     * @param routeId 工艺路线业务 ID，应与 route.routeId 一致
     * @param route 该工单使用的完整工艺路线快照
     * @param productSkuId 产品 SKU ID
     * @param productSkuCode 产品 SKU 编码
     * @param planNum 计划总数量，必须大于 0
     * @param maxOrderQuantity 单张工单最大制单数量，必须大于 0；算法据此拆分
     * @param priority 计划优先级，数字越小越优先
     * @param urgent 是否加急；加急计划优先于普通计划
     * @param planStartTime 计划允许开始时间
     * @param planEndTime 计划要求完成时间，也是 BACKWARD 算法的计算基准
     * @param attributes 调用方扩展属性，第一版不解释
     */
    public record Task(
            String taskId,
            String parentTaskId,
            String orderCode,
            String saleOrderCode,
            String routeId,
            Route route,
            String productSkuId,
            String productSkuCode,
            BigDecimal planNum,
            BigDecimal maxOrderQuantity,
            int priority,
            boolean urgent,
            LocalDateTime planStartTime,
            LocalDateTime planEndTime,
            Map<String, String> attributes
    ) {}

    /**
     * 工艺路线快照。
     *
     * @param routeId 路线唯一 ID
     * @param routeCode 路线编码
     * @param routeName 路线名称
     * @param stepList 完整工序列表，至少包含一项
     * @param stepEdgeList 工序前后关系；单工序时允许为空
     */
    public record Route(
            String routeId,
            String routeCode,
            String routeName,
            List<Step> stepList,
            List<StepEdge> stepEdgeList
    ) {}

    /**
     * 路线工序，字段名与 ncloud 的 WorkshopPlanStep 保持一致。
     *
     * @param stepId 工序唯一 ID
     * @param stepMark 工序标记
     * @param stepCode 工序编码
     * @param stepName 工序名称
     * @param lastStep 是否末工序，第一版保留该业务标记
     * @param needDevice 是否必须选择设备；为真时至少提供一个 DEVICE 候选
     * @param prepareCost 准备工时，单位为秒
     * @param processCost 加工工时，单位为秒
     * @param resourceList 工序候选资源
     * @param attributes 调用方扩展属性，第一版不解释
     */
    public record Step(
            String stepId,
            String stepMark,
            String stepCode,
            String stepName,
            boolean lastStep,
            boolean needDevice,
            Integer prepareCost,
            Integer processCost,
            List<StepResource> resourceList,
            Map<String, String> attributes
    ) {}

    /**
     * 工序有向关系，字段名与 ncloud 的 WorkshopPlanStepEdge 保持一致。
     *
     * @param startStepId 前工序 ID
     * @param endStepId 后工序 ID
     * @param minIntervalMinutes 前后工序最小间隔分钟数；为空时使用全局参数
     * @param maxIntervalMinutes 最大间隔分钟数，0表示不限制
     */
    public record StepEdge(
            String startStepId,
            String endStepId,
            Integer minIntervalMinutes,
            Integer maxIntervalMinutes
    ) {}

    /**
     * 工序候选资源，主要字段与 ncloud 的 WorkshopPlanStepResource 保持一致。
     * 同类型的多个资源为候选其一，不同类型的资源会组合占用。
     *
     * @param resourceType 资源类型
     * @param resourceId 顶层资源列表中的资源 ID
     * @param sort 候选优先顺序，数字越小越优先
     * @param hasDefault 是否默认资源，默认资源优先
     * @param takeTime 单件加工节拍，单位为秒/件
     * @param takeRate 节拍倍率，空值或非正数按 1 处理
     * @param replaceTime 换型时间，单位为分钟
     * @param assignType ncloud 分配类型，同时间结果中数字较小者优先
     * @param requiredCapacity 本工序需要占用的资源容量
     * @param required 是否必选；同类型全部为 false 时允许不选择该类资源
     * @param continuous 是否必须在一个连续工作区间内完成
     * @param attributes 调用方扩展属性，第一版不解释
     */
    public record StepResource(
            ResourceType resourceType,
            String resourceId,
            int sort,
            boolean hasDefault,
            Integer takeTime,
            BigDecimal takeRate,
            Integer replaceTime,
            Integer assignType,
            BigDecimal requiredCapacity,
            boolean required,
            boolean continuous,
            Map<String, String> attributes
    ) {}

}
