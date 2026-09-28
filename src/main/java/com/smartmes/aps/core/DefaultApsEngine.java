package com.smartmes.aps.core;

import com.smartmes.aps.api.ApsEngine;
import com.smartmes.aps.api.ApsRequest;
import com.smartmes.aps.api.ApsResult;
import com.smartmes.aps.api.EngineInfo;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.smartmes.aps.api.ApsResult.Severity.ERROR;
import static com.smartmes.aps.api.ApsResult.Severity.WARNING;

/**
 * 第一版 APS 算法实现：确定性有限产能启发式排产。
 * <p>
 * 算法按加急、优先级和交期确定工单顺序，再按工艺拓扑逐道安排工序。
 * 它从班次生成工作区间，扣除外部占用和本次已分配容量，并在候选资源组合中
 * 选择正排最早完成或倒排最晚开始的方案。实现类不持有跨请求可变状态。
 */
public final class DefaultApsEngine implements ApsEngine {
    public static final String ENGINE_VERSION = "0.2.4";
    public static final String HEURISTIC_SOLVER_ID = "HEURISTIC";
    public static final String GENETIC_SOLVER_ID = "GENETIC";
    public static final String FORWARD_ALGORITHM_ID = "FORWARD";
    public static final String BACKWARD_ALGORITHM_ID = "BACKWARD";
    public static final String ALGORITHM_VERSION = "2.1.1";
    private static final Set<String> SUPPORTED_ALGORITHM_IDS =
            Set.of(FORWARD_ALGORITHM_ID, BACKWARD_ALGORITHM_ID);
    private static final ProgressListener NO_PROGRESS = (id, stage, percent, message) -> {};
    private static final CancellationToken NEVER_CANCEL = () -> false;

    @Override
    public ApsResult schedule(ApsRequest request) {
        return schedule(request, NO_PROGRESS, NEVER_CANCEL);
    }

    @Override
    public ApsResult schedule(ApsRequest request, ProgressListener listener, CancellationToken cancellationToken) {
        ApsEngineProgress progress = new ApsEngineProgress(request == null ? null : request.requestId(),
                listener == null ? NO_PROGRESS : listener);
        CancellationToken cancel = cancellationToken == null ? NEVER_CANCEL : cancellationToken;
        Instant startedAt = Instant.now();
        progress.report("VALIDATING", 0, "校验排产输入");
        List<ApsResult.Issue> issues = new ArrayList<>(validate(request));
        if (issues.stream().anyMatch(i -> i.severity() == ERROR)) {
            return emptyResult(request, ApsResult.Status.INVALID_INPUT, startedAt, issues);
        }

        try {
            progress.report("SCHEDULING", 10, "开始自动排产");
            ApsRequest.AlgorithmParam param = request.algorithmParam() == null
                    ? ApsRequest.AlgorithmParam.defaults() : request.algorithmParam();
            SearchOutcome outcome = solverType(request) == ApsRequest.SolverType.GENETIC
                    ? scheduleGenetic(request, param, progress, cancel, issues, startedAt)
                    : scheduleHeuristic(request, param, progress, cancel, issues, startedAt);
            progress.report("FINALIZING", 95, outcome.message() + "，正在整理算法结果");
            ApsResult result = combineCandidates(request, startedAt, outcome.results());
            progress.report("COMPLETED", 100, outcome.message() + "，算法结果已整理");
            return result;
        } catch (ControlException e) {
            progress.report(e.status.name(), progress.percent(), e.getMessage());
            issues.add(issue(WARNING, e.status == ApsResult.Status.TIMEOUT ? "APS-ENGINE-002" : "APS-ENGINE-003",
                    e.getMessage(), "REQUEST", request.requestId()));
            return emptyResult(request, e.status, startedAt, issues);
        } catch (RuntimeException e) {
            progress.report("FAILED", progress.percent(), "算法执行失败：" + safeMessage(e));
            issues.add(issue(ERROR, "APS-ENGINE-999", "算法内部错误：" + safeMessage(e),
                    "REQUEST", request.requestId()));
            return emptyResult(request, ApsResult.Status.INTERNAL_ERROR, startedAt, issues);
        }
    }

    private static SearchOutcome scheduleHeuristic(ApsRequest request, ApsRequest.AlgorithmParam param,
            ApsEngineProgress progress, CancellationToken cancel, List<ApsResult.Issue> issues, Instant startedAt) {
        ApsRequest.HeuristicParam heuristic = param.heuristicParam() == null
                ? ApsRequest.HeuristicParam.defaults() : param.heuristicParam();
        int searchCount = Math.min(100, Math.max(1, heuristic.candidateSearchCount()));
        int combinationLimit = Math.min(4096, Math.max(1, heuristic.maxResourceCombinationsPerStep()));
        long deadlineNanos = deadlineNanos(param);
        List<EvaluatedResult> results = new ArrayList<>();
        Map<String, Long> workBySource = estimateWork(request, param, combinationLimit, cancel);
        for (int index = 0; index < searchCount; index++) {
            cancelCheck(cancel);
            int candidateIndex = index;
            String context = "启发式候选 " + (index + 1) + "/" + searchCount;
            EvaluationProgress candidateProgress = (fraction, message) -> progress.report("SCHEDULING",
                    10 + 85D * (candidateIndex + fraction) / searchCount, () -> context + "，" + message.get());
            results.add(new Scheduler(request, candidateProgress, cancel, issues, index,
                    null, heuristic.enableCandidateOrderPerturbation(), combinationLimit, deadlineNanos, workBySource).evaluate(startedAt));
            candidateProgress.update(1, "候选计算完成");
        }
        return new SearchOutcome(results, "已完成 " + searchCount + "/" + searchCount + " 个启发式候选");
    }

    private static SearchOutcome scheduleGenetic(ApsRequest request, ApsRequest.AlgorithmParam param,
            ApsEngineProgress progress, CancellationToken cancel, List<ApsResult.Issue> issues, Instant startedAt) {
        ApsRequest.GeneticParam genetic = param.geneticParam() == null
                ? ApsRequest.GeneticParam.defaults() : param.geneticParam();
        int populationSize = Math.min(200, Math.max(2, genetic.populationSize()));
        int generations = Math.min(1000, Math.max(1, genetic.maxGenerations()));
        int eliteCount = Math.min(populationSize - 1, Math.max(1, genetic.eliteCount()));
        int tournamentSize = Math.min(populationSize, Math.max(2, genetic.tournamentSize()));
        double crossoverRate = ratio(genetic.crossoverRate(), 0.80);
        double mutationRate = ratio(genetic.mutationRate(), 0.10);
        double seedRate = ratio(genetic.heuristicSeedRate(), 0.30);
        Random random = new Random(request.randomSeed());
        long deadlineNanos = deadlineNanos(param);
        Map<String, Long> workBySource = estimateWork(request, param, 4096, cancel);
        List<String> operationIds = operationIds(request);
        List<String> resourceGeneIds = resourceGeneIds(request);
        List<Chromosome> population = initialPopulation(operationIds, resourceGeneIds,
                populationSize, seedRate, random);
        List<EvaluatedResult> allResults = new ArrayList<>();
        ApsRequest.OutputParam output = request.outputParam() == null
                ? ApsRequest.OutputParam.defaults() : request.outputParam();
        int archiveLimit = output.includeCandidates() ? Math.max(1, output.candidateLimit()) : 1;
        EvaluatedResult bestEvaluation = null;
        int stagnant = 0;
        int evaluationIndex = 0;
        int completedGenerations = 0;
        boolean converged = false;
        for (int generation = 0; generation < generations; generation++) {
            cancelCheck(cancel);
            int individual = 0;
            for (Chromosome chromosome : population) {
                int slot = evaluationIndex++;
                String context = "遗传算法第 " + (generation + 1) + "/" + generations
                        + " 代，个体 " + (++individual) + "/" + populationSize;
                EvaluationProgress individualProgress = (fraction, message) -> progress.report("EVOLVING",
                        10 + 85D * (slot + fraction) / ((long) generations * populationSize),
                        () -> context + "，" + message.get());
                EvaluatedResult result = new Scheduler(request, individualProgress, cancel, issues, slot,
                        chromosome, true, 4096, deadlineNanos, workBySource).evaluate(startedAt);
                chromosome.evaluation = result;
                allResults.add(result);
                individualProgress.update(1, "个体计算完成");
            }
            // 先按实际排法去重再截断，避免重复精英占满候选档案。
            allResults = distinctEvaluations(allResults, archiveLimit);
            population.sort((left, right) -> compareChromosomes(right, left));
            EvaluatedResult generationBest = population.get(0).evaluation;
            if (bestEvaluation == null || EVALUATION_ORDER.compare(generationBest, bestEvaluation) > 0) {
                bestEvaluation = generationBest;
                stagnant = 0;
            } else {
                stagnant++;
            }
            completedGenerations = generation + 1;
            progress.report("EVOLVING", 10 + 85D * completedGenerations / generations,
                    "遗传算法已完成 " + completedGenerations + "/" + generations + " 代，本代个体评估已完成");
            if (genetic.stagnationGenerations() > 0 && stagnant >= genetic.stagnationGenerations()
                    && completedGenerations < generations) {
                converged = true;
                progress.report("CONVERGED", progress.percent(), "遗传算法已提前收敛，连续 " + stagnant
                        + " 代评分及同分排序未改善，实际完成 " + completedGenerations + "/" + generations + " 代");
                break;
            }
            List<Chromosome> next = new ArrayList<>();
            for (int index = 0; index < eliteCount; index++) {
                next.add(population.get(index).copyWithoutResult());
            }
            while (next.size() < populationSize) {
                Chromosome first = tournament(population, tournamentSize, random);
                Chromosome second = tournament(population, tournamentSize, random);
                Chromosome child = random.nextDouble() < crossoverRate
                        ? crossover(first, second, random) : first.copyWithoutResult();
                if (random.nextDouble() < mutationRate) {
                    mutate(child, random);
                }
                next.add(child);
            }
            population = next;
        }
        return new SearchOutcome(allResults, "遗传算法" + (converged ? "已提前收敛" : "搜索完成")
                + "，实际完成 " + completedGenerations + "/" + generations + " 代、"
                + evaluationIndex + "/" + ((long) generations * populationSize) + " 个个体");
    }

    /** 不完整方案仅在搜索内部评分，仍不作为可保存方案返回。 */
    private record EvaluatedResult(ApsResult result, BigDecimal fitness,
            BigInteger totalCompletionSeconds, ApsCandidateKey key) {}

    /** 数值越大越优；同分时累计完成耗时越小越优。 */
    private static final Comparator<EvaluatedResult> EVALUATION_ORDER = Comparator
            .comparing(EvaluatedResult::fitness)
            .thenComparing(EvaluatedResult::totalCompletionSeconds, Comparator.reverseOrder());

    private static List<EvaluatedResult> distinctEvaluations(List<EvaluatedResult> results, int limit) {
        Map<ApsCandidateKey, EvaluatedResult> unique = new LinkedHashMap<>();
        results.stream().sorted(EVALUATION_ORDER.reversed())
                .forEach(result -> unique.putIfAbsent(result.key(), result));
        return new ArrayList<>(unique.values().stream().limit(Math.max(0, limit)).toList());
    }

    private record SearchOutcome(List<EvaluatedResult> results, String message) {}

    @FunctionalInterface
    private interface EvaluationProgress {
        void update(double fraction, Supplier<String> message);

        default void update(double fraction, String message) {
            update(fraction, () -> message);
        }
    }

    /** 资源组合数只取决于输入快照；每个请求统计一次，各候选和个体共用。 */
    private static Map<String, Long> estimateWork(ApsRequest request, ApsRequest.AlgorithmParam param,
            int limit, CancellationToken cancel) {
        Set<ResourceKey> resources = list(request.resourceList()).stream()
                .map(resource -> new ResourceKey(resource.resourceType(), resource.resourceId()))
                .collect(Collectors.toSet());
        Map<String, Long> work = new HashMap<>();
        for (ApsRequest.Task task : request.taskList()) {
            cancelCheck(cancel);
            long taskWork = 0;
            for (ApsRequest.Step step : task.route().stepList()) {
                Map<ApsRequest.ResourceType, Integer> counts = new EnumMap<>(ApsRequest.ResourceType.class);
                Set<ApsRequest.ResourceType> required = EnumSet.noneOf(ApsRequest.ResourceType.class);
                for (ApsRequest.StepResource option : list(step.resourceList())) {
                    if (option.resourceType() == ApsRequest.ResourceType.TOOLING && !param.enableToolingConstraint()
                            || !resources.contains(new ResourceKey(option.resourceType(), option.resourceId()))) continue;
                    counts.merge(option.resourceType(), 1, Integer::sum);
                    if (option.required() || step.needDevice() && option.resourceType() == ApsRequest.ResourceType.DEVICE) {
                        required.add(option.resourceType());
                    }
                }
                long combinations = 1;
                if (!step.needDevice() || counts.containsKey(ApsRequest.ResourceType.DEVICE)) {
                    for (var entry : counts.entrySet()) {
                        int size = entry.getValue() + (required.contains(entry.getKey()) ? 0 : 1);
                        combinations = Math.min(limit, combinations * size);
                    }
                }
                // 缺少必需设备的工序也占一个处理单位，失败后跳过的工序在工单结束时结算。
                taskWork += Math.max(1, combinations);
            }
            work.put(task.taskId(), Math.max(1, taskWork));
        }
        return work;
    }

    private static ApsRequest.SolverType solverType(ApsRequest request) {
        return request == null || request.solverType() == null
                ? ApsRequest.SolverType.HEURISTIC : request.solverType();
    }

    private static double ratio(BigDecimal value, double fallback) {
        return value == null ? fallback : Math.max(0D, Math.min(1D, value.doubleValue()));
    }

    private static long deadlineNanos(ApsRequest.AlgorithmParam param) {
        Duration timeout = param.timeout() == null || param.timeout().isNegative() || param.timeout().isZero()
                ? Duration.ofMinutes(10) : param.timeout();
        return System.nanoTime() + timeout.toNanos();
    }

    private static List<String> operationIds(ApsRequest request) {
        List<String> result = new ArrayList<>();
        for (ApsRequest.Task task : request.taskList()) {
            int count = task.planNum().divide(task.maxOrderQuantity(), 0, RoundingMode.CEILING).intValueExact();
            for (int index = 1; index <= count; index++) {
                result.add(task.taskId() + "-SPLIT-" + String.format("%03d", index));
            }
        }
        return result;
    }

    private static List<String> resourceGeneIds(ApsRequest request) {
        List<String> result = new ArrayList<>();
        for (ApsRequest.Task task : request.taskList()) {
            int count = task.planNum().divide(task.maxOrderQuantity(), 0, RoundingMode.CEILING).intValueExact();
            for (int index = 1; index <= count; index++) {
                String splitId = task.taskId() + "-SPLIT-" + String.format("%03d", index);
                for (ApsRequest.Step step : list(task.route().stepList())) {
                    for (ApsRequest.StepResource resource : list(step.resourceList())) {
                        result.add(resourceGeneId(splitId, step.stepId(), resource));
                    }
                }
            }
        }
        return result.stream().distinct().toList();
    }

    private static String resourceGeneId(String splitTaskId, String stepId, ApsRequest.StepResource resource) {
        return splitTaskId + "/" + stepId + "/" + resource.resourceType() + "/" + resource.resourceId();
    }

    private static List<Chromosome> initialPopulation(List<String> operations, List<String> resources,
            int size, double seedRate, Random random) {
        List<Chromosome> result = new ArrayList<>();
        int seedCount = Math.max(1, (int) Math.round(size * seedRate));
        for (int index = 0; index < size; index++) {
            List<String> order = new ArrayList<>(operations);
            List<String> resourceOrder = new ArrayList<>(resources);
            if (index >= seedCount) {
                Collections.shuffle(order, random);
                Collections.shuffle(resourceOrder, random);
            } else if (index > 0) {
                swapRandom(order, random);
                swapRandom(resourceOrder, random);
            }
            Map<String, Integer> ranks = new HashMap<>();
            for (int rank = 0; rank < resourceOrder.size(); rank++) {
                ranks.put(resourceOrder.get(rank), rank);
            }
            result.add(new Chromosome(order, ranks));
        }
        return result;
    }

    private static int compareChromosomes(Chromosome left, Chromosome right) {
        return Comparator.nullsFirst(EVALUATION_ORDER).compare(left.evaluation, right.evaluation);
    }

    private static Chromosome tournament(List<Chromosome> population, int size, Random random) {
        Chromosome best = null;
        for (int index = 0; index < size; index++) {
            Chromosome candidate = population.get(random.nextInt(population.size()));
            if (best == null || compareChromosomes(candidate, best) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static Chromosome crossover(Chromosome first, Chromosome second, Random random) {
        List<String> childOrder = orderedCrossover(first.operationOrder, second.operationOrder, random);
        Map<String, Integer> childResources = new HashMap<>();
        Set<String> keys = new LinkedHashSet<>(first.resourceRanks.keySet());
        keys.addAll(second.resourceRanks.keySet());
        for (String key : keys) {
            Integer value = random.nextBoolean() ? first.resourceRanks.get(key) : second.resourceRanks.get(key);
            if (value == null) {
                value = first.resourceRanks.getOrDefault(key, second.resourceRanks.getOrDefault(key, 0));
            }
            childResources.put(key, value);
        }
        return new Chromosome(childOrder, childResources);
    }

    private static List<String> orderedCrossover(List<String> first, List<String> second, Random random) {
        if (first.size() < 2) {
            return new ArrayList<>(first);
        }
        int left = random.nextInt(first.size());
        int right = random.nextInt(first.size());
        if (left > right) {
            int value = left;
            left = right;
            right = value;
        }
        List<String> result = new ArrayList<>(Collections.nCopies(first.size(), null));
        Set<String> used = new HashSet<>();
        for (int index = left; index <= right; index++) {
            result.set(index, first.get(index));
            used.add(first.get(index));
        }
        int write = (right + 1) % first.size();
        for (int offset = 0; offset < second.size(); offset++) {
            String value = second.get((right + 1 + offset) % second.size());
            if (used.add(value)) {
                while (result.get(write) != null) {
                    write = (write + 1) % result.size();
                }
                result.set(write, value);
            }
        }
        return result;
    }

    private static void mutate(Chromosome chromosome, Random random) {
        swapRandom(chromosome.operationOrder, random);
        List<String> keys = new ArrayList<>(chromosome.resourceRanks.keySet());
        if (keys.size() >= 2) {
            String first = keys.get(random.nextInt(keys.size()));
            String second = keys.get(random.nextInt(keys.size()));
            Integer rank = chromosome.resourceRanks.get(first);
            chromosome.resourceRanks.put(first, chromosome.resourceRanks.get(second));
            chromosome.resourceRanks.put(second, rank);
        }
    }

    private static <T> void swapRandom(List<T> values, Random random) {
        if (values.size() >= 2) {
            Collections.swap(values, random.nextInt(values.size()), random.nextInt(values.size()));
        }
    }

    private static void cancelCheck(CancellationToken cancellation) {
        if (cancellation.isCancellationRequested()) {
            throw new ControlException(ApsResult.Status.CANCELLED, "排产已取消");
        }
    }

    private static ApsResult combineCandidates(ApsRequest request, Instant startedAt,
                                                List<EvaluatedResult> evaluations) {
        ApsRequest.OutputParam output = request.outputParam() == null
                ? ApsRequest.OutputParam.defaults() : request.outputParam();
        List<EvaluatedResult> ranked = distinctEvaluations(evaluations, Math.max(1, output.candidateLimit()));
        ApsResult best = ranked.get(0).result();
        List<ApsResult.Solution> candidates = output.includeCandidates() && best.bestSolution() != null
                ? ranked.stream().map(EvaluatedResult::result).map(ApsResult::bestSolution)
                        .filter(Objects::nonNull).limit(Math.max(0, output.candidateLimit())).toList()
                : List.of();
        Instant completedAt = Instant.now();
        return new ApsResult(best.schemaVersion(), best.requestId(), best.status(), best.engineVersion(),
                best.solverType(), best.algorithmId(), best.algorithmVersion(), best.randomSeed(), startedAt, completedAt,
                Duration.between(startedAt, completedAt).toMillis(), best.bestSolution(), candidates,
                best.issueList(), best.inputDigest(), best.statistics());
    }

    @Override
    public List<ApsResult.Issue> validate(ApsRequest request) {
        List<ApsResult.Issue> issues = new ArrayList<>();
        if (request == null) {
            issues.add(issue(ERROR, "APS-INPUT-000", "请求不能为空", "REQUEST", null));
            return issues;
        }
        required(request.schemaVersion(), "schemaVersion", issues);
        required(request.requestId(), "requestId", issues);
        required(request.algorithmId(), "algorithmId", issues);
        if (!SUPPORTED_ALGORITHM_IDS.contains(request.algorithmId())) {
            issues.add(issue(ERROR, "APS-INPUT-001", "不支持的算法ID：" + request.algorithmId(), "REQUEST", request.requestId()));
        }
        if (request.currentTime() == null) {
            issues.add(issue(ERROR, "APS-INPUT-002", "currentTime不能为空", "REQUEST", request.requestId()));
        }
        validateAlgorithmParam(request, issues);
        // 后续校验需要将班次本地时间转换为绝对时间。
        // 公共前置字段缺失时立即返回，避免“校验方法本身”因空值抛出异常。
        if (request.currentTime() == null) {
            return List.copyOf(issues);
        }

        validateShifts(request, issues);
        Map<ResourceKey, ApsRequest.Resource> resources = validateResources(request, issues);
        validateTasks(request, resources, issues);
        return List.copyOf(issues);
    }

    private static void validateAlgorithmParam(ApsRequest request, List<ApsResult.Issue> issues) {
        ApsRequest.AlgorithmParam param = request.algorithmParam();
        if (param == null) {
            return;
        }
        ApsRequest.HeuristicParam heuristic = param.heuristicParam();
        if (solverType(request) == ApsRequest.SolverType.HEURISTIC && heuristic != null
                && (heuristic.candidateSearchCount() <= 0
                || heuristic.maxResourceCombinationsPerStep() <= 0)) {
            issues.add(issue(ERROR, "APS-HEURISTIC-001",
                    "启发式搜索次数和单工序最大资源组合数必须大于0", "REQUEST", request.requestId()));
        }
        ApsRequest.GeneticParam genetic = param.geneticParam();
        if (solverType(request) == ApsRequest.SolverType.GENETIC && genetic != null) {
            if (genetic.populationSize() < 2 || genetic.maxGenerations() <= 0
                    || genetic.eliteCount() <= 0 || genetic.eliteCount() >= genetic.populationSize()
                    || genetic.tournamentSize() < 2 || genetic.tournamentSize() > genetic.populationSize()) {
                issues.add(issue(ERROR, "APS-GENETIC-001",
                        "遗传算法要求种群>=2、代数>0、精英数在[1,种群-1]且锦标赛规模在[2,种群]",
                        "REQUEST", request.requestId()));
            }
            if (!validRatio(genetic.crossoverRate()) || !validRatio(genetic.mutationRate())
                    || !validRatio(genetic.heuristicSeedRate())) {
                issues.add(issue(ERROR, "APS-GENETIC-002",
                        "遗传算法交叉率、变异率和启发式种子占比必须在0到1之间",
                        "REQUEST", request.requestId()));
            }
        }
    }

    private static boolean validRatio(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) >= 0 && value.compareTo(BigDecimal.ONE) <= 0;
    }

    @Override
    public EngineInfo getEngineInfo() {
        EngineInfo.AlgorithmDescriptor heuristic = new EngineInfo.AlgorithmDescriptor(
                HEURISTIC_SOLVER_ID,
                "有限产能启发式排产",
                ALGORITHM_VERSION,
                "按业务优先级快速构造有限产能方案，可独立选择正排或倒排",
                EnumSet.allOf(ApsRequest.ResourceType.class),
                Set.of("ROUTE_PRECEDENCE", "SHIFT_CALENDAR", "RESOURCE_CAPACITY",
                        "EXISTING_OCCUPANCY", "PROCESS_INTERVAL",
                        "PARENT_TASK_PRECEDENCE", "PIECE_TRANSFER"),
                true, false, true, true);
        EngineInfo.AlgorithmDescriptor genetic = new EngineInfo.AlgorithmDescriptor(
                GENETIC_SOLVER_ID,
                "遗传算法排产",
                ALGORITHM_VERSION,
                "通过染色体选择、交叉和变异搜索工单顺序及资源偏好，并由有限产能解码器生成方案",
                EnumSet.allOf(ApsRequest.ResourceType.class),
                Set.of("ROUTE_PRECEDENCE", "SHIFT_CALENDAR", "RESOURCE_CAPACITY",
                        "EXISTING_OCCUPANCY", "PROCESS_INTERVAL", "PARENT_TASK_PRECEDENCE", "PIECE_TRANSFER"),
                true, false, true, true);
        return new EngineInfo(ENGINE_VERSION, Set.of("1.0"), List.of(heuristic, genetic),
                SUPPORTED_ALGORITHM_IDS);
    }

    /** 校验班次日期唯一性、时间方向以及同一天的班次重叠。 */
    private static void validateShifts(ApsRequest request, List<ApsResult.Issue> issues) {
        if (request.shiftScheduleList() == null || request.shiftScheduleList().isEmpty()) {
            issues.add(issue(WARNING, "DEFAULT_24X7_CALENDAR", "未传班次排班，按7×24小时计算", "REQUEST", request.requestId()));
            return;
        }
        Set<LocalDate> dates = new HashSet<>();
        for (ApsRequest.ShiftSchedule day : request.shiftScheduleList()) {
            if (day == null || day.scheduleDate() == null || !dates.add(day.scheduleDate())) {
                issues.add(issue(ERROR, "APS-SHIFT-001", "排班日期为空或重复", "SHIFT_SCHEDULE",
                        day == null ? null : String.valueOf(day.scheduleDate())));
                continue;
            }
            List<Interval> intervals = new ArrayList<>();
            for (ApsRequest.Shift shift : list(day.shiftList())) {
                if (shift == null || shift.startTime() == null || shift.endTime() == null) {
                    issues.add(issue(ERROR, "APS-SHIFT-002", "班次开始、结束时间不能为空", "SHIFT", null));
                    continue;
                }
                LocalDateTime start = day.scheduleDate().atTime(shift.startTime());
                LocalDateTime end = day.scheduleDate().plusDays(shift.crossDay() ? 1 : 0)
                        .atTime(shift.endTime());
                if (!start.isBefore(end)) {
                    issues.add(issue(ERROR, "APS-SHIFT-003", "班次开始时间必须早于结束时间，跨日班次需设置crossDay=true",
                            "SHIFT", shift.shiftId()));
                    continue;
                }
                Interval current = new Interval(start, end);
                if (intervals.stream().anyMatch(current::overlaps)) {
                    issues.add(issue(ERROR, "APS-SHIFT-004", "同一日期的班次时间不能重叠", "SHIFT", shift.shiftId()));
                }
                intervals.add(current);
            }
        }
    }

    /** 校验资源唯一性和占用时段，并建立后续路线校验使用的资源索引。 */
    private static Map<ResourceKey, ApsRequest.Resource> validateResources(
            ApsRequest request, List<ApsResult.Issue> issues) {
        Map<ResourceKey, ApsRequest.Resource> result = new LinkedHashMap<>();
        for (ApsRequest.Resource resource : list(request.resourceList())) {
            if (resource == null || resource.resourceType() == null || blank(resource.resourceId())) {
                issues.add(issue(ERROR, "APS-RESOURCE-001", "资源类型和资源ID不能为空", "RESOURCE", null));
                continue;
            }
            ResourceKey key = new ResourceKey(resource.resourceType(), resource.resourceId());
            if (result.putIfAbsent(key, resource) != null) {
                issues.add(issue(ERROR, "APS-RESOURCE-002", "资源类型和资源ID重复", "RESOURCE", resource.resourceId()));
            }
            BigDecimal capacity = capacity(resource.capacity());
            for (ApsRequest.ResourceOccupancy occupancy : list(resource.occupancyList())) {
                if (occupancy == null || occupancy.startTime() == null || occupancy.endTime() == null
                        || !occupancy.startTime().isBefore(occupancy.endTime())) {
                    issues.add(issue(ERROR, "APS-RESOURCE-003", "资源占用开始时间必须早于结束时间",
                            "RESOURCE", resource.resourceId()));
                } else if (capacity(occupancy.capacityUsed()).compareTo(capacity) > 0) {
                    issues.add(issue(ERROR, "APS-RESOURCE-004", "单条资源占用容量不能超过资源总容量",
                            "RESOURCE", resource.resourceId()));
                }
            }
        }
        return result;
    }

    /** 校验工单唯一性、数量、时间范围、内嵌路线和父工单引用。 */
    private static void validateTasks(ApsRequest request, Map<ResourceKey, ApsRequest.Resource> resources,
            List<ApsResult.Issue> issues) {
        if (request.taskList() == null || request.taskList().isEmpty()) {
            issues.add(issue(ERROR, "APS-TASK-001", "taskList不能为空", "REQUEST", request.requestId()));
            return;
        }
        Set<String> ids = new HashSet<>();
        for (ApsRequest.Task task : request.taskList()) {
            if (task == null || blank(task.taskId()) || !ids.add(task.taskId())) {
                issues.add(issue(ERROR, "APS-TASK-002", "任务ID为空或重复", "TASK", task == null ? null : task.taskId()));
                continue;
            }
            if (task.route() == null) {
                issues.add(issue(ERROR, "APS-TASK-003", "任务必须携带完整工艺路线", "TASK", task.taskId()));
            } else {
                if (blank(task.routeId()) || !Objects.equals(task.routeId(), task.route().routeId())) {
                    issues.add(issue(ERROR, "APS-TASK-007", "任务routeId必须与内嵌route.routeId一致",
                            "TASK", task.taskId()));
                }
                validateRoute(task.route(), task.taskId(), resources, issues);
            }
            if (task.planNum() == null || task.planNum().signum() <= 0) {
                issues.add(issue(ERROR, "APS-TASK-004", "任务planNum必须大于0", "TASK", task.taskId()));
            }
            if (task.maxOrderQuantity() == null || task.maxOrderQuantity().signum() <= 0) {
                issues.add(issue(ERROR, "APS-TASK-008", "任务maxOrderQuantity必须大于0", "TASK", task.taskId()));
            }
            if (task.planStartTime() == null || task.planEndTime() == null
                    || !task.planStartTime().isBefore(task.planEndTime())) {
                issues.add(issue(ERROR, "APS-TASK-005", "任务planStartTime必须早于planEndTime", "TASK", task.taskId()));
            }
        }
        Set<String> allIds = new HashSet<>(ids);
        for (ApsRequest.Task task : request.taskList()) {
            if (task != null && !blank(task.parentTaskId()) && !allIds.contains(task.parentTaskId())) {
                issues.add(issue(ERROR, "APS-TASK-006", "父任务不存在", "TASK", task.taskId()));
            }
        }
    }

    /** 校验单个任务内嵌路线、工序、候选资源引用和有向无环关系。 */
    private static void validateRoute(ApsRequest.Route route, String taskId,
            Map<ResourceKey, ApsRequest.Resource> resources, List<ApsResult.Issue> issues) {
        if (blank(route.routeId())) {
            issues.add(issue(ERROR, "APS-ROUTE-001", "工艺路线ID不能为空", "TASK", taskId));
        }
        Map<String, ApsRequest.Step> steps = new LinkedHashMap<>();
        for (ApsRequest.Step step : list(route.stepList())) {
            if (step == null || blank(step.stepId())) {
                issues.add(issue(ERROR, "APS-STEP-001", "工序ID不能为空", "TASK", taskId));
                continue;
            }
            if (steps.putIfAbsent(step.stepId(), step) != null) {
                issues.add(issue(ERROR, "APS-STEP-002", "同一路线工序ID重复", "STEP", taskId + "/" + step.stepId()));
            }
            String transferMode = step.attributes() == null ? null : step.attributes().get("transferMode");
            if (transferMode != null && !Set.of("BATCH", "PIECE").contains(transferMode)) {
                issues.add(issue(ERROR, "APS-STEP-TRANSFER", "流转方式只支持BATCH或PIECE", "STEP", taskId + "/" + step.stepId()));
            }
            for (ApsRequest.StepResource option : list(step.resourceList())) {
                if (option == null || option.resourceType() == null || blank(option.resourceId())) {
                    issues.add(issue(ERROR, "APS-STEP-003", "工序候选资源类型和ID不能为空",
                            "STEP", taskId + "/" + step.stepId()));
                } else if (!resources.containsKey(new ResourceKey(option.resourceType(), option.resourceId()))) {
                    issues.add(issue(ERROR, "APS-STEP-004", "工序候选资源不存在于resourceList：" + option.resourceId(),
                            "STEP", taskId + "/" + step.stepId()));
                }
            }
            if (step.needDevice() && list(step.resourceList()).stream()
                    .noneMatch(option -> option != null
                            && option.resourceType() == ApsRequest.ResourceType.DEVICE)) {
                issues.add(issue(ERROR, "APS-STEP-005", "needDevice=true时至少需要一个DEVICE候选资源",
                        "STEP", taskId + "/" + step.stepId()));
            }
        }
        if (steps.isEmpty()) {
            issues.add(issue(ERROR, "APS-ROUTE-005", "工艺路线至少需要一个工序", "TASK", taskId));
        }
        Map<String, Integer> indegree = new HashMap<>();
        steps.keySet().forEach(id -> indegree.put(id, 0));
        Map<String, List<String>> next = new HashMap<>();
        for (ApsRequest.StepEdge edge : list(route.stepEdgeList())) {
            if (edge == null || !steps.containsKey(edge.startStepId()) || !steps.containsKey(edge.endStepId())) {
                issues.add(issue(ERROR, "APS-ROUTE-003", "工序关系引用不存在的工序", "TASK", taskId));
                continue;
            }
            next.computeIfAbsent(edge.startStepId(), k -> new ArrayList<>()).add(edge.endStepId());
            indegree.compute(edge.endStepId(), (k, v) -> v + 1);
        }
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                queue.add(id);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String id = queue.remove();
            visited++;
            for (String target : next.getOrDefault(id, List.of())) {
                if (indegree.compute(target, (k, v) -> v - 1) == 0) {
                    queue.add(target);
                }
            }
        }
        if (visited != steps.size()) {
            issues.add(issue(ERROR, "APS-ROUTE-004", "工艺路线存在环路", "TASK", taskId));
        }
    }

    /** 构造没有可用方案的标准结果，确保错误场景仍返回完整元数据。 */
    private static ApsResult emptyResult(ApsRequest request, ApsResult.Status status,
            Instant startedAt, List<ApsResult.Issue> issues) {
        Instant completedAt = Instant.now();
        return new ApsResult(
                request == null ? "1.0" : request.schemaVersion(),
                request == null ? null : request.requestId(), status, ENGINE_VERSION,
                request == null ? null : solverType(request), request == null ? null : request.algorithmId(), ALGORITHM_VERSION,
                request == null ? 0 : request.randomSeed(), startedAt, completedAt,
                Duration.between(startedAt, completedAt).toMillis(), null, List.of(), List.copyOf(issues), null,
                new ApsResult.Statistics(0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    /**
     * 单次请求的排产上下文。
     * 所有资源占用、路线索引和统计都封装在此对象中，排产结束后即可释放，
     * 因而不会在多个请求之间共享可变状态。
     */
    private static final class Scheduler {
        private final ApsRequest request;
        private final ApsRequest.AlgorithmParam param;
        private final EvaluationProgress progress;
        private final CancellationToken cancellation;
        private final List<ApsResult.Issue> issues;
        private final Map<ResourceKey, ResourceState> resourceMap = new LinkedHashMap<>();
        /** 所有任务最早允许开始时间，由 taskList 自动计算。 */
        private final LocalDateTime rangeStartTime;
        /** 所有任务最晚要求结束时间，由 taskList 自动计算。 */
        private final LocalDateTime rangeEndTime;
        /** 逆向排产严格失败后允许补排的最大延期。 */
        private final Duration lateCompletionLimit;
        private final long deadlineNanos;
        private int stepCount;
        private int scheduledStepCount;
        private int defaultDurationStepCount;
        private final int candidateIndex;
        private final Chromosome chromosome;
        private final boolean perturbCandidateOrder;
        private final int resourceCombinationLimit;
        private final Map<String, Long> workBySource;
        private long totalWork;
        private long completedTaskWork;
        private long taskWork;
        private long attemptWork;
        private double taskHighWater;
        private int taskOrdinal;
        private int taskTotal;
        private int stepOrdinal;
        private int stepTotal;
        private boolean retrying;
        private BigDecimal evaluationScore = BigDecimal.ZERO;
        private BigInteger totalCompletionSeconds = BigInteger.ZERO;
        private ApsCandidateKey candidateKey;
        private final Map<String, LocalDateTime> completedTaskEndMap = new HashMap<>();
        private final Map<String, LocalDateTime> completedTaskStartMap = new HashMap<>();

        private Scheduler(ApsRequest request, EvaluationProgress progress,
                CancellationToken cancellation, List<ApsResult.Issue> issues, int candidateIndex,
                Chromosome chromosome, boolean perturbCandidateOrder, int resourceCombinationLimit,
                long deadlineNanos, Map<String, Long> workBySource) {
            this.request = request;
            this.param = request.algorithmParam() == null ? ApsRequest.AlgorithmParam.defaults() : request.algorithmParam();
            this.progress = progress;
            this.cancellation = cancellation;
            this.issues = new ArrayList<>(issues);
            this.candidateIndex = candidateIndex;
            this.chromosome = chromosome;
            this.perturbCandidateOrder = perturbCandidateOrder;
            this.resourceCombinationLimit = resourceCombinationLimit;
            this.workBySource = workBySource;
            this.rangeStartTime = request.taskList().stream()
                    .map(ApsRequest.Task::planStartTime).min(LocalDateTime::compareTo).orElseThrow();
            LocalDateTime latestPlanEnd = request.taskList().stream()
                    .map(ApsRequest.Task::planEndTime).max(LocalDateTime::compareTo).orElseThrow();
            this.lateCompletionLimit = param.lateCompletionLimit() == null
                    || param.lateCompletionLimit().isNegative() || param.lateCompletionLimit().isZero()
                    ? Duration.ofDays(30) : param.lateCompletionLimit();
            if (isForward()) {
                // 正向排产的交期是评分依据而不是硬边界；搜索到调用方提供的最后一个资源班次。
                this.rangeEndTime = latestCalendarEnd(latestPlanEnd);
            } else {
                this.rangeEndTime = param.allowLateCompletion()
                        ? latestPlanEnd.plus(lateCompletionLimit) : latestPlanEnd;
            }
            this.deadlineNanos = deadlineNanos;
            for (ApsRequest.Resource resource : list(request.resourceList())) {
                ResourceState state = new ResourceState(resource);
                if (param.appendMode()) {
                    for (ApsRequest.ResourceOccupancy occupancy : list(resource.occupancyList())) {
                        state.usageList.add(new Usage(occupancy.startTime(), occupancy.endTime(),
                                capacity(occupancy.capacityUsed()), true));
                    }
                }
                resourceMap.put(new ResourceKey(resource.resourceType(), resource.resourceId()), state);
            }
        }

        private LocalDateTime latestCalendarEnd(LocalDateTime latestPlanEnd) {
            if (request.shiftScheduleList() == null || request.shiftScheduleList().isEmpty()) {
                // 没有班次快照代表7×24小时；使用协议支持的安全最大日期，不引入业务延期天数。
                return LocalDateTime.of(9999, 12, 31, 23, 59, 59);
            }
            LocalDateTime latest = null;
            for (ApsRequest.ShiftSchedule schedule : request.shiftScheduleList()) {
                if (schedule == null || schedule.scheduleDate() == null) {
                    continue;
                }
                for (ApsRequest.Shift shift : list(schedule.shiftList())) {
                    if (shift == null || shift.endTime() == null) {
                        continue;
                    }
                    LocalDateTime end = schedule.scheduleDate()
                            .plusDays(shift.crossDay() ? 1 : 0).atTime(shift.endTime());
                    if (latest == null || end.isAfter(latest)) {
                        latest = end;
                    }
                }
            }
            return latest == null || latest.isBefore(latestPlanEnd) ? latestPlanEnd : latest;
        }

        private EvaluatedResult evaluate(Instant startedAt) {
            ApsResult result = run(startedAt);
            // 完整可执行方案始终优先；不完整方案用相同指标引导搜索，不能成为保存候选。
            BigDecimal fitness = result.bestSolution() == null
                    ? evaluationScore.subtract(BigDecimal.valueOf(101)) : evaluationScore;
            return new EvaluatedResult(result, fitness, totalCompletionSeconds, candidateKey);
        }

        /** 先按最大制单数量拆分计划，再按业务优先级安排拆分工单。 */
        private ApsResult run(Instant startedAt) {
            List<SplitTask> tasks = splitTasks();
            Map<String, Long> workByTask = new HashMap<>();
            for (SplitTask task : tasks) {
                checkControl();
                long work = workBySource.get(task.source.taskId());
                workByTask.put(task.splitTaskId, Math.max(1, work));
                totalWork += Math.max(1, work);
            }
            taskTotal = tasks.size();
            Comparator<SplitTask> comparator = Comparator
                    .comparingInt((SplitTask item) -> dependencyDepth(item.source(), new HashSet<>()));
            if (chromosome != null) {
                comparator = comparator.thenComparingInt(item -> chromosome.operationRank(item.splitTaskId()));
            }
            tasks.sort(comparator
                    .thenComparing(Comparator.comparing((SplitTask item) -> item.source.urgent()).reversed())
                    .thenComparingInt(item -> item.source.priority())
                    .thenComparing(item -> item.source.planEndTime())
                    .thenComparingLong(item -> stableTieKey(item.source.taskId()))
                    .thenComparing(item -> item.source.taskId())
                    .thenComparingInt(SplitTask::splitNo));

            List<ApsResult.TaskResult> results = new ArrayList<>();
            for (SplitTask task : tasks) {
                checkControl();
                taskOrdinal++;
                taskWork = workByTask.get(task.splitTaskId);
                taskHighWater = 0;
                progress.update((double) completedTaskWork / totalWork,
                        "工单 " + taskOrdinal + "/" + taskTotal + "，正在展开工序");
                ApsResult.TaskResult result = scheduleTask(task);
                completedTaskWork += taskWork;
                progress.update((double) completedTaskWork / totalWork, "已处理工单 " + taskOrdinal + "/" + taskTotal
                        + (result == null ? "，本工单不可排，剩余工序已跳过" : "，本工单计算完成"));
                if (result != null) {
                    results.add(result);
                    completedTaskEndMap.merge(task.source().taskId(), result.planEndTime(),
                            (left, right) -> left.isAfter(right) ? left : right);
                    completedTaskStartMap.merge(task.source().taskId(), result.planStartTime(),
                            (left, right) -> left.isBefore(right) ? left : right);
                }
            }

            results.sort(Comparator.comparing(ApsResult.TaskResult::taskId)
                    .thenComparingInt(ApsResult.TaskResult::splitNo));
            int scheduledTaskCount = results.size();
            int unscheduledTaskCount = tasks.size() - scheduledTaskCount;
            int onTime = (int) results.stream().filter(ApsResult.TaskResult::onTime).count();
            int late = scheduledTaskCount - onTime;
            LocalDateTime solutionStart = results.stream().map(ApsResult.TaskResult::planStartTime)
                    .min(LocalDateTime::compareTo).orElse(null);
            LocalDateTime solutionEnd = results.stream().map(ApsResult.TaskResult::planEndTime)
                    .max(LocalDateTime::compareTo).orElse(null);
            BigDecimal score = new ApsScoring(request).score(tasks.size(), scheduledTaskCount, onTime, solutionEnd);
            evaluationScore = score;
            LocalDateTime completionBaseline = max(request.currentTime(), rangeStartTime);
            totalCompletionSeconds = results.stream().map(task -> BigInteger.valueOf(Math.max(0,
                    Duration.between(completionBaseline, task.planEndTime()).getSeconds())))
                    .reduce(BigInteger.ZERO, BigInteger::add);
            // 必须在输出选项删去资源和工作段之前生成去重依据。
            candidateKey = ApsCandidateKey.from(results);
            boolean feasible = unscheduledTaskCount == 0;
            List<ApsResult.ScheduleFlowCard> cardList = buildCardList(results);
            List<ApsResult.SchedulePlanItem> itemList = buildItemList(results);
            ApsResult.Solution rawSolution = results.isEmpty() || !feasible ? null
                    : new ApsResult.Solution(request.requestId() + "-S" + (candidateIndex + 1), feasible, score,
                            solutionStart, solutionEnd, cardList, itemList, List.copyOf(results));
            ApsRequest.OutputParam output = request.outputParam() == null
                    ? ApsRequest.OutputParam.defaults() : request.outputParam();
            ApsResult.Solution solution = rawSolution == null ? null : applyOutputOptions(rawSolution, output);
            List<ApsResult.Solution> candidates = output.includeCandidates() && solution != null
                    && output.candidateLimit() > 0 ? List.of(solution) : List.of();
            ApsResult.Status status = feasible ? ApsResult.Status.SUCCESS : ApsResult.Status.INFEASIBLE;
            Instant completedAt = Instant.now();
            return new ApsResult(request.schemaVersion(), request.requestId(), status, ENGINE_VERSION,
                    solverType(request), request.algorithmId(), ALGORITHM_VERSION, request.randomSeed(), startedAt, completedAt,
                    Duration.between(startedAt, completedAt).toMillis(), solution, candidates,
                    output.includeDiagnostics() ? List.copyOf(issues) : List.of(),
                    output.includeInputDigest() ? inputDigest(tasks) : null,
                    new ApsResult.Statistics(request.taskList().size(), tasks.size(),
                            scheduledTaskCount, unscheduledTaskCount,
                            stepCount, scheduledStepCount, defaultDurationStepCount, onTime, late));
        }

        private int dependencyDepth(ApsRequest.Task task, Set<String> visiting) {
            if (blank(task.parentTaskId()) || !visiting.add(task.taskId())) {
                return 0;
            }
            ApsRequest.Task parent = request.taskList().stream()
                    .filter(item -> Objects.equals(item.taskId(), task.parentTaskId())).findFirst().orElse(null);
            int depth = parent == null ? 0 : dependencyDepth(parent, visiting) + 1;
            visiting.remove(task.taskId());
            return isForward() ? depth : -depth;
        }

        private ApsResult.InputDigest inputDigest(List<SplitTask> tasks) {
            int occupancyCount = list(request.resourceList()).stream()
                    .mapToInt(item -> list(item.occupancyList()).size()).sum();
            return new ApsResult.InputDigest(request.taskList().size(), list(request.resourceList()).size(),
                    list(request.shiftScheduleList()).size(), occupancyCount, stepCount);
        }

        private ApsResult.Solution applyOutputOptions(ApsResult.Solution source, ApsRequest.OutputParam output) {
            if (output.includeResourceTimeline()) {
                return source;
            }
            List<ApsResult.TaskResult> tasks = source.taskList().stream().map(task -> new ApsResult.TaskResult(
                    task.taskId(), task.splitTaskId(), task.splitNo(), task.splitCount(), task.planNum(),
                    task.orderCode(), task.requiredStartTime(), task.requiredEndTime(), task.planStartTime(),
                    task.planEndTime(), task.onTime(), task.latenessMinutes(), task.stepList().stream()
                    .map(step -> new ApsResult.StepResult(step.splitTaskId(), step.stepId(), step.stepCode(),
                            step.stepName(), step.planStartTime(), step.planEndTime(), step.prepareCost(),
                            step.processCost(), step.defaultDurationApplied(), List.of(), List.of())).toList())).toList();
            return new ApsResult.Solution(source.solutionId(), source.feasible(), source.score(),
                    source.planStartTime(), source.planEndTime(), source.cardList(), source.itemList(), tasks);
        }

        /**
         * 将拆分批次转换为 ncloud 的排产流转卡结构。
         * 此处生成的是跨项目可识别的逻辑 ID 和临时编码，ncloud 保存前必须换成自身的正式 ID/业务编码。
         */
        private List<ApsResult.ScheduleFlowCard> buildCardList(List<ApsResult.TaskResult> results) {
            return results.stream().map(result -> {
                ApsRequest.Task task = taskById(result.taskId());
                String cardCode = temporaryCardCode(task, result.splitNo());
                return new ApsResult.ScheduleFlowCard(result.splitTaskId(), null, cardCode,
                        result.planNum(), "0", attribute(task, "workshopId"), task.taskId());
            }).toList();
        }

        /**
         * 将算法工序结果平铺为 ncloud SchedulePlan.itemList。
         * 每个拆分批次的每道工序生成一条明细，设备、班组、时间、数量和工时可直接用于工单生成。
         */
        private List<ApsResult.SchedulePlanItem> buildItemList(List<ApsResult.TaskResult> results) {
            List<ApsResult.SchedulePlanItem> items = new ArrayList<>();
            for (ApsResult.TaskResult result : results) {
                ApsRequest.Task task = taskById(result.taskId());
                String cardCode = temporaryCardCode(task, result.splitNo());
                Map<String, ApsRequest.Step> stepMap = task.route().stepList().stream()
                        .collect(Collectors.toMap(ApsRequest.Step::stepId, Function.identity()));
                int stepNo = 0;
                for (ApsResult.StepResult stepResult : result.stepList()) {
                    ApsRequest.Step step = stepMap.get(stepResult.stepId());
                    ApsResult.ResourceAssignment device = assignment(stepResult, ApsRequest.ResourceType.DEVICE);
                    ApsResult.ResourceAssignment team = assignment(stepResult, ApsRequest.ResourceType.TEAM);
                    String itemId = result.splitTaskId() + "-" + stepResult.stepId();
                    String orderCode = temporaryOrderCode(task, result.splitNo(), ++stepNo);
                    items.add(new ApsResult.SchedulePlanItem(
                            itemId, null, orderCode, task.taskId(), result.splitTaskId(), null,
                            stepResult.stepId(), id(device), id(team),
                            stepResult.planStartTime(), stepResult.planEndTime(),
                            stepResult.prepareCost(), stepResult.processCost(), null, result.planNum(),
                            "0", 0, task.urgent(), "0", null,
                            stepResult.stepCode(), stepResult.stepName(), step.stepMark(),
                            attribute(step.attributes(), "stepDesc"), step.lastStep(),
                            code(device), name(device), code(team), name(team),
                            task.productSkuCode(), attribute(task, "productSkuDesc"),
                            attribute(task, "productSpuCode"), attribute(task, "productSpuName"),
                            attribute(task, "productSpuModel"), attribute(task, "productSpuSpec"),
                            attribute(task, "spuImg"), attribute(task, "spuUnit"), attribute(task, "spuAttr"),
                            task.orderCode(), attribute(task, "workshopCode"), attribute(task, "workshopName"),
                            cardCode));
                }
            }
            return List.copyOf(items);
        }

        private ApsRequest.Task taskById(String taskId) {
            return request.taskList().stream().filter(task -> Objects.equals(task.taskId(), taskId))
                    .findFirst().orElseThrow();
        }

        private static ApsResult.ResourceAssignment assignment(
                ApsResult.StepResult step, ApsRequest.ResourceType type) {
            return step.resourceList().stream().filter(item -> item.resourceType() == type).findFirst().orElse(null);
        }

        private static String id(ApsResult.ResourceAssignment value) {
            return value == null ? null : value.resourceId();
        }

        private static String code(ApsResult.ResourceAssignment value) {
            return value == null ? null : value.resourceCode();
        }

        private static String name(ApsResult.ResourceAssignment value) {
            return value == null ? null : value.resourceName();
        }

        private static String attribute(ApsRequest.Task task, String name) {
            return attribute(task.attributes(), name);
        }

        private static String attribute(Map<String, String> attributes, String name) {
            return attributes == null ? null : attributes.get(name);
        }

        private static String temporaryCardCode(ApsRequest.Task task, int splitNo) {
            return (blank(task.orderCode()) ? task.taskId() : task.orderCode())
                    + "-CARD-" + String.format("%03d", splitNo);
        }

        private static String temporaryOrderCode(ApsRequest.Task task, int splitNo, int stepNo) {
            return (blank(task.orderCode()) ? task.taskId() : task.orderCode())
                    + "-WO-" + String.format("%03d-%03d", splitNo, stepNo);
        }

        /** 将每条待排产计划按 maxOrderQuantity 拆成数量稳定、顺序可复现的工单。 */
        private List<SplitTask> splitTasks() {
            List<SplitTask> result = new ArrayList<>();
            for (ApsRequest.Task task : request.taskList()) {
                int count = task.planNum().divide(task.maxOrderQuantity(), 0, RoundingMode.CEILING).intValueExact();
                BigDecimal remaining = task.planNum();
                for (int index = 1; index <= count; index++) {
                    BigDecimal quantity = remaining.min(task.maxOrderQuantity());
                    String splitTaskId = task.taskId() + "-SPLIT-" + String.format("%03d", index);
                    result.add(new SplitTask(task, splitTaskId, index, count, quantity));
                    remaining = remaining.subtract(quantity);
                }
            }
            return result;
        }

        /**
         * 安排一个工单的全部工序。
         * 任一工序不可排时撤销该工单已经产生的临时占用，避免污染后续工单。
         */
        private ApsResult.TaskResult scheduleTask(SplitTask splitTask) {
            ApsRequest.Task task = splitTask.source;
            ApsRequest.Route route = task.route();
            List<ApsRequest.Step> ordered = topological(route);
            stepCount += ordered.size();
            ApsResult.TaskResult result = scheduleTaskAttempt(splitTask, ordered, isForward(), false);
            if (result == null && !isForward() && param.allowLateCompletion()) {
                // 先严格倒排；只有失败工单才从最早可用时间正向补排，避免主动排到最大延期日。
                result = scheduleTaskAttempt(splitTask, ordered, true, true);
            }
            if (result == null) {
                String failedStepId = ordered.isEmpty() ? null : ordered.get(0).stepId();
                issues.add(issue(WARNING, "APS-CONSTRAINT-001", "工序在时间范围内没有可行资源时间",
                        "STEP", splitTask.splitTaskId + "/" + failedStepId));
            }
            return result;
        }

        private ApsResult.TaskResult scheduleTaskAttempt(SplitTask splitTask,
                                                         List<ApsRequest.Step> ordered,
                                                         boolean forward,
                                                         boolean lateFallback) {
            ApsRequest.Task task = splitTask.source;
            ApsRequest.Route route = task.route();
            List<ApsRequest.Step> traversal = new ArrayList<>(ordered);
            if (!forward) {
                java.util.Collections.reverse(traversal);
            }

            Map<String, ApsResult.StepResult> stepResults = new HashMap<>();
            List<ReservedUsage> tentativeReservations = new ArrayList<>();
            attemptWork = 0;
            stepOrdinal = 0;
            stepTotal = traversal.size();
            retrying = lateFallback;
            for (ApsRequest.Step step : traversal) {
                stepOrdinal++;
                checkControl();
                LocalDateTime boundary = forward
                        ? earliestBoundary(task, step, route, stepResults, splitTask.planNum)
                        : latestBoundary(task, step, route, stepResults);
                Placement placement = choosePlacement(
                        splitTask, step, boundary, stepResults, forward, lateFallback);
                if (placement == null) {
                    rollback(tentativeReservations);
                    return null;
                }
                for (ChosenResource chosen : placement.chosenResources) {
                    for (Interval segment : placement.segments) {
                        Usage usage = new Usage(segment.start, segment.end, chosen.requiredCapacity, false);
                        chosen.state.usageList.add(usage);
                        tentativeReservations.add(new ReservedUsage(chosen.state, usage));
                    }
                }
                List<ApsResult.ResourceAssignment> assignments = placement.chosenResources.stream()
                        .map(c -> new ApsResult.ResourceAssignment(c.state.resource.resourceType(),
                                c.state.resource.resourceId(), c.state.resource.resourceCode(),
                                c.state.resource.resourceName(), c.requiredCapacity))
                        .toList();
                List<ApsResult.TimeSegment> segments = placement.segments.stream()
                        .map(i -> new ApsResult.TimeSegment(i.start, i.end)).toList();
                ApsResult.StepResult result = new ApsResult.StepResult(splitTask.splitTaskId, step.stepId(),
                        step.stepCode(), step.stepName(), placement.start, placement.end,
                        placement.prepareSeconds, placement.processSeconds, placement.defaultDuration,
                        assignments, segments);
                stepResults.put(step.stepId(), result);
            }

            List<ApsResult.StepResult> outputSteps = ordered.stream().map(s -> stepResults.get(s.stepId())).toList();
            scheduledStepCount += outputSteps.size();
            defaultDurationStepCount += (int) outputSteps.stream().filter(ApsResult.StepResult::defaultDurationApplied).count();
            outputSteps.stream().filter(ApsResult.StepResult::defaultDurationApplied).forEach(step ->
                    issues.add(issue(WARNING, "APS-DURATION-DEFAULT-480",
                            "准备工时和加工工时均未提供，工序总工时按8小时处理",
                            "STEP", splitTask.splitTaskId + "/" + step.stepId())));
            LocalDateTime start = outputSteps.stream().map(ApsResult.StepResult::planStartTime)
                    .min(LocalDateTime::compareTo).orElseThrow();
            LocalDateTime end = outputSteps.stream().map(ApsResult.StepResult::planEndTime)
                    .max(LocalDateTime::compareTo).orElseThrow();
            boolean onTime = !end.isAfter(task.planEndTime());
            long lateness = onTime ? 0 : Duration.between(task.planEndTime(), end).toMinutes();
            return new ApsResult.TaskResult(task.taskId(), splitTask.splitTaskId,
                    splitTask.splitNo, splitTask.splitCount, splitTask.planNum,
                    task.orderCode(), task.planStartTime(), task.planEndTime(),
                    start, end, onTime, lateness, outputSteps);
        }

        /** 计算正排工序的最早边界，包括当前时间、工单窗口和前工序间隔。 */
        private LocalDateTime earliestBoundary(ApsRequest.Task task, ApsRequest.Step step, ApsRequest.Route route,
                Map<String, ApsResult.StepResult> results, BigDecimal quantity) {
            LocalDateTime value = max(request.currentTime(), rangeStartTime, task.planStartTime());
            if (!blank(task.parentTaskId())) {
                LocalDateTime parentEnd = completedTaskEndMap.get(task.parentTaskId());
                if (parentEnd != null && parentEnd.isAfter(value)) {
                    value = parentEnd;
                }
            }
            for (ApsRequest.StepEdge edge : list(route.stepEdgeList())) {
                if (Objects.equals(edge.endStepId(), step.stepId())) {
                    ApsResult.StepResult predecessor = results.get(edge.startStepId());
                    if (predecessor != null) {
                        int gap = edge.minIntervalMinutes() == null ? param.minProcessIntervalMinutes() : edge.minIntervalMinutes();
                        LocalDateTime candidate = releaseTime(predecessor, routeStep(route, edge.startStepId()), quantity)
                                .plus(Duration.ofMinutes(Math.max(0, gap)));
                        if (candidate.isAfter(value)) {
                            value = candidate;
                        }
                    }
                }
            }
            return value;
        }

        /** 计算倒排工序的最晚边界，包括工单交期和后工序最小间隔。 */
        private LocalDateTime latestBoundary(ApsRequest.Task task, ApsRequest.Step step, ApsRequest.Route route,
                Map<String, ApsResult.StepResult> results) {
            LocalDateTime value = min(rangeEndTime, task.planEndTime());
            LocalDateTime childStart = request.taskList().stream()
                    .filter(item -> Objects.equals(task.taskId(), item.parentTaskId()))
                    .map(item -> completedTaskStartMap.get(item.taskId())).filter(Objects::nonNull)
                    .min(LocalDateTime::compareTo).orElse(null);
            if (childStart != null && childStart.isBefore(value)) {
                value = childStart;
            }
            for (ApsRequest.StepEdge edge : list(route.stepEdgeList())) {
                if (!isPieceTransfer(step) && Objects.equals(edge.startStepId(), step.stepId())) {
                    ApsResult.StepResult successor = results.get(edge.endStepId());
                    if (successor != null) {
                        int gap = edge.minIntervalMinutes() == null ? param.minProcessIntervalMinutes() : edge.minIntervalMinutes();
                        LocalDateTime candidate = successor.planStartTime().minus(Duration.ofMinutes(Math.max(0, gap)));
                        if (candidate.isBefore(value)) {
                            value = candidate;
                        }
                    }
                }
            }
            return value;
        }

        /**
         * 枚举工序候选资源组合并选择最佳位置。
         * 正排优先选择结束最早的组合，倒排优先选择开始最晚的组合；同分时选择资源顺序更小者。
         */
        private Placement choosePlacement(SplitTask splitTask, ApsRequest.Step step, LocalDateTime boundary,
                                          Map<String, ApsResult.StepResult> stepResults,
                                          boolean forward, boolean lateFallback) {
            ApsRequest.Task task = splitTask.source;
            List<List<ChosenResource>> groups = resourceGroups(splitTask, step);
            if (groups == null) {
                return null;
            }
            List<List<ChosenResource>> combinations = new ArrayList<>();
            int searchLimit = Math.min(4096, Math.max(1, resourceCombinationLimit));
            combinations(groups, 0, new ArrayList<>(), combinations, searchLimit);
            if (combinations.isEmpty()) {
                combinations.add(List.of());
            }

            AtomicInteger completed = new AtomicInteger();
            reportCombinations(0, combinations.size());
            Placement result;
            if (param.maxParallelism() <= 1 || combinations.size() <= 1) {
                result = null;
                for (List<ChosenResource> combination : combinations) {
                    checkControl();
                    Placement candidate = placementForCombination(splitTask, step, boundary, stepResults,
                            combination, forward, lateFallback);
                    reportCombinations(completed.incrementAndGet(), combinations.size());
                    if (candidate != null && (result == null || !better(result, candidate, forward))) result = candidate;
                }
            } else {
                checkControl();
                ForkJoinPool pool = new ForkJoinPool(Math.min(param.maxParallelism(), combinations.size()));
                try {
                    var future = pool.submit(() -> combinations.parallelStream().map(combination -> {
                        try {
                            return placementForCombination(splitTask, step, boundary, stepResults, combination,
                                    forward, lateFallback);
                        } finally {
                            completed.incrementAndGet();
                        }
                    }).filter(Objects::nonNull).reduce((left, right) -> better(left, right, forward) ? left : right)
                            .orElse(null));
                    while (true) {
                        try {
                            result = future.get(200, TimeUnit.MILLISECONDS);
                            break;
                        } catch (TimeoutException waiting) {
                            checkControl();
                            reportCombinations(completed.get(), combinations.size());
                        }
                    }
                    checkControl();
                    reportCombinations(completed.get(), combinations.size());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ControlException(ApsResult.Status.CANCELLED, "排产已取消");
                } catch (ExecutionException execution) {
                    if (execution.getCause() instanceof RuntimeException cause) throw cause;
                    throw new IllegalStateException("资源组合计算失败", execution.getCause());
                } finally {
                    pool.shutdownNow();
                }
            }
            attemptWork += combinations.size();
            return result;
        }

        /** 所有回调在排产主线程执行，工作线程只计数，避免丢失调用方租户上下文。 */
        private void reportCombinations(int completed, int total) {
            double resolved = attemptWork + completed;
            if (!isForward() && param.allowLateCompletion()) {
                // 严格倒排和可能的延期补排各占一半；无需补排时在工单结束结算剩余预算。
                resolved = (retrying ? taskWork * 0.5 : 0) + resolved * 0.5;
            }
            taskHighWater = Math.max(taskHighWater, Math.min(taskWork, resolved));
            progress.update((completedTaskWork + taskHighWater) / Math.max(1, totalWork),
                    () -> (retrying ? "逆向不可排，正在延期补排，" : "")
                            + "工单 " + taskOrdinal + "/" + taskTotal + "，工序 " + stepOrdinal + "/" + stepTotal
                            + "，已评估资源组合 " + completed + "/" + total);
        }

        private Placement placementForCombination(SplitTask splitTask, ApsRequest.Step step,
                                                   LocalDateTime boundary,
                                                   Map<String, ApsResult.StepResult> stepResults,
                                                   List<ChosenResource> combination,
                                                   boolean forward, boolean lateFallback) {
            ApsRequest.Task task = splitTask.source;
            DurationInfo duration = duration(splitTask.planNum, step, combination);
            LocalDateTime lower = max(rangeStartTime, task.planStartTime());
            LocalDateTime upper = forward
                    ? lateFallback
                            ? min(rangeEndTime, task.planEndTime().plus(lateCompletionLimit))
                            : rangeEndTime
                    : min(rangeEndTime, task.planEndTime());
            List<Interval> available = availableSegments(combination, lower, upper);
            boolean continuous = combination.stream().anyMatch(c -> c.option.continuous());
            LocalDateTime firstPieceDeadline = null;
            if (!forward && isPieceTransfer(step)) {
                for (ApsRequest.StepEdge edge : list(task.route().stepEdgeList())) {
                    ApsResult.StepResult successor = stepResults.get(edge.endStepId());
                    if (Objects.equals(edge.startStepId(), step.stepId()) && successor != null) {
                        int gap = edge.minIntervalMinutes() == null ? param.minProcessIntervalMinutes() : edge.minIntervalMinutes();
                        LocalDateTime deadline = successor.planStartTime().minusMinutes(Math.max(0, gap));
                        firstPieceDeadline = firstPieceDeadline == null ? deadline : min(firstPieceDeadline, deadline);
                    }
                }
            }
            List<Interval> allocated = forward
                    ? allocateForward(available, boundary, duration.totalSeconds, continuous)
                    : firstPieceDeadline == null
                            ? allocateBackward(available, boundary, duration.totalSeconds, continuous)
                            : allocateBackwardSinglePiece(available, boundary, firstPieceDeadline,
                                    duration.totalSeconds, firstPieceSeconds(duration.totalSeconds, splitTask.planNum), continuous);
            if (allocated.isEmpty()) {
                return null;
            }
            Placement candidate = new Placement(allocated.get(0).start,
                    allocated.get(allocated.size() - 1).end, allocated, combination,
                    duration.prepareSeconds, duration.processSeconds, duration.defaultDuration,
                    combination.stream().mapToInt(c -> c.option.sort()
                            + Math.max(0, c.option.assignType() == null ? 0 : c.option.assignType())).sum(),
                    combination.stream().mapToInt(c -> chromosome == null ? 0
                            : chromosome.resourceRank(resourceGeneId(
                                    splitTask.splitTaskId(), step.stepId(), c.option))).sum());
            return withinMaximumInterval(step, task.route(), candidate, stepResults, forward, splitTask.planNum)
                    ? candidate : null;
        }

        private boolean better(Placement candidate, Placement current, boolean forward) {
            if (current == null) {
                return true;
            }
            if (chromosome != null && candidate.preferenceRank != current.preferenceRank) {
                return candidate.preferenceRank < current.preferenceRank;
            }
            int compare = forward
                    ? candidate.end.compareTo(current.end) : current.start.compareTo(candidate.start);
            if (compare != 0) {
                return compare < 0;
            }
            if (candidate.priority != current.priority) {
                return candidate.priority < current.priority;
            }
            long candidateKey = candidate.chosenResources.stream().mapToLong(c ->
                    stableTieKey(c.option.resourceType() + ":" + c.option.resourceId())).sum();
            long currentKey = current.chosenResources.stream().mapToLong(c ->
                    stableTieKey(c.option.resourceType() + ":" + c.option.resourceId())).sum();
            return candidateKey < currentKey;
        }

        private long stableTieKey(String value) {
            long variant = perturbCandidateOrder ? candidateIndex + 1L : 1L;
            long hash = request.randomSeed() ^ (0x9E3779B97F4A7C15L * variant);
            for (int i = 0; i < value.length(); i++) {
                hash = (hash ^ value.charAt(i)) * 0x100000001B3L;
            }
            return hash & Long.MAX_VALUE;
        }

        private boolean withinMaximumInterval(ApsRequest.Step step, ApsRequest.Route route,
                                              Placement placement,
                                              Map<String, ApsResult.StepResult> results,
                                              boolean forward, BigDecimal quantity) {
            for (ApsRequest.StepEdge edge : list(route.stepEdgeList())) {
                int maximum = edge.maxIntervalMinutes() == null
                        ? Math.max(0, param.maxProcessIntervalHours()) * 60
                        : Math.max(0, edge.maxIntervalMinutes());
                if (maximum == 0) {
                    continue;
                }
                if (forward && Objects.equals(edge.endStepId(), step.stepId())) {
                    ApsResult.StepResult predecessor = results.get(edge.startStepId());
                    if (predecessor != null && Duration.between(
                            releaseTime(predecessor, routeStep(route, edge.startStepId()), quantity), placement.start).compareTo(Duration.ofMinutes(maximum)) > 0) {
                        return false;
                    }
                } else if (!forward && Objects.equals(edge.startStepId(), step.stepId())) {
                    ApsResult.StepResult successor = results.get(edge.endStepId());
                    if (successor != null && Duration.between(
                            isPieceTransfer(step) ? completionAfter(placement.segments,
                                    firstPieceSeconds(placement.prepareSeconds + placement.processSeconds, quantity)) : placement.end,
                            successor.planStartTime()).compareTo(Duration.ofMinutes(maximum)) > 0) {
                        return false;
                    }
                }
            }
            return true;
        }

        /**
         * 按资源类型建立候选组。同类型资源为“候选其一”，不同类型资源需要组合占用。
         * 返回 {@code null} 表示工序声明必须使用设备，但没有可用设备候选。
         */
        private List<List<ChosenResource>> resourceGroups(SplitTask splitTask, ApsRequest.Step step) {
            Map<ApsRequest.ResourceType, List<ChosenResource>> map = new EnumMap<>(ApsRequest.ResourceType.class);
            for (ApsRequest.StepResource option : list(step.resourceList())) {
                if (option.resourceType() == ApsRequest.ResourceType.TOOLING && !param.enableToolingConstraint()) {
                    continue;
                }
                ResourceState state = resourceMap.get(new ResourceKey(option.resourceType(), option.resourceId()));
                if (state == null) {
                    continue;
                }
                BigDecimal required = capacity(option.requiredCapacity());
                map.computeIfAbsent(option.resourceType(), k -> new ArrayList<>())
                        .add(new ChosenResource(option, state, required));
            }
            if (step.needDevice() && !map.containsKey(ApsRequest.ResourceType.DEVICE)) {
                return null;
            }
            List<List<ChosenResource>> groups = new ArrayList<>();
            for (Map.Entry<ApsRequest.ResourceType, List<ChosenResource>> entry : map.entrySet()) {
                List<ChosenResource> values = entry.getValue();
                boolean required = entry.getKey() == ApsRequest.ResourceType.DEVICE && step.needDevice()
                        || values.stream().anyMatch(item -> item.option.required());
                values.sort(Comparator.comparing((ChosenResource c) -> !c.option.hasDefault())
                        .thenComparingInt(c -> chromosome == null ? 0
                                : chromosome.resourceRank(resourceGeneId(splitTask.splitTaskId(), step.stepId(), c.option)))
                        .thenComparingInt(c -> c.option.sort())
                        .thenComparingLong(c -> stableTieKey(c.option.resourceId())));
                if (!required) {
                    values.add(null);
                }
                groups.add(values);
            }
            return groups;
        }

        /** 深度优先生成资源组合，并用上限避免异常输入导致组合数量失控。 */
        private void combinations(List<List<ChosenResource>> groups, int index, List<ChosenResource> current,
                List<List<ChosenResource>> output, int limit) {
            if (output.size() >= limit) {
                return;
            }
            if (index == groups.size()) {
                output.add(List.copyOf(current));
                return;
            }
            for (ChosenResource option : groups.get(index)) {
                if (option != null) {
                    current.add(option);
                }
                combinations(groups, index + 1, current, output, limit);
                if (option != null) {
                    current.remove(current.size() - 1);
                }
            }
        }

        /** 根据路线秒级工时、工单数量、资源节拍、换型时间和倍率计算实际秒数。 */
        private DurationInfo duration(BigDecimal planNum, ApsRequest.Step step, List<ChosenResource> chosen) {
            // 工艺路线未给出准备/加工工时，且所选资源也没有单件节拍时，才按 8 小时兜底。
            boolean hasResourceRate = chosen.stream().map(c -> c.option)
                    .anyMatch(o -> o.takeTime() != null && o.takeTime() > 0);
            boolean defaultDuration = positive(step.prepareCost()) == 0
                    && positive(step.processCost()) == 0 && !hasResourceRate;
            if (defaultDuration) {
                return new DurationInfo(0, 8 * 60 * 60, 8 * 60 * 60, true);
            }

            int replace = chosen.stream().map(c -> c.option.replaceTime()).filter(Objects::nonNull)
                    .mapToInt(Integer::intValue).max().orElse(0);
            // replaceTime 使用分钟，准备/加工工时和单件节拍使用秒；所有工时均为单件值。
            BigDecimal rhythmSeconds = chosen.stream().map(c -> c.option)
                    .filter(o -> o.takeTime() != null && o.takeTime() > 0)
                    .map(o -> BigDecimal.valueOf(o.takeTime()).multiply(orOne(o.takeRate())))
                    .max(BigDecimal::compareTo).orElse(null);
            BigDecimal unitPrepare = BigDecimal.valueOf(rhythmSeconds != null
                    ? Math.multiplyExact(Math.max(0, replace), 60) : positive(step.prepareCost()));
            BigDecimal unitProcess = rhythmSeconds != null
                    ? rhythmSeconds : BigDecimal.valueOf(positive(step.processCost()));
            int prepare = planNum.multiply(unitPrepare).setScale(0, RoundingMode.CEILING).intValueExact();
            int process = planNum.multiply(unitProcess).multiply(orOne(param.globalTimeScale()))
                    .setScale(0, RoundingMode.CEILING).intValueExact();
            return new DurationInfo(prepare, Math.max(0, process),
                    Math.addExact(prepare, Math.max(0, process)), defaultDuration);
        }

        /**
         * 计算一组资源共同可用的原子时间段。
         * 先求各资源班次交集，再按所有占用边界切段，逐段检查“已用容量 + 本工序容量”。
         */
        private List<Interval> availableSegments(List<ChosenResource> chosen, LocalDateTime lower, LocalDateTime upper) {
            List<Interval> base;
            if (chosen.isEmpty()) {
                base = workingIntervals(null, lower, upper);
            } else {
                LocalDateTime start = max(rangeStartTime, lower);
                LocalDateTime end = min(rangeEndTime, upper);
                base = start.isBefore(end) ? List.of(new Interval(start, end)) : List.of();
            }
            for (ChosenResource resource : chosen) {
                base = intersect(base, workingIntervals(resource.state, lower, upper));
            }
            List<Interval> result = new ArrayList<>();
            for (Interval interval : base) {
                Set<LocalDateTime> boundarySet = new LinkedHashSet<>();
                boundarySet.add(interval.start);
                boundarySet.add(interval.end);
                for (ChosenResource resource : chosen) {
                    for (Usage usage : resource.state.usageList) {
                        if (usage.overlaps(interval)) {
                            boundarySet.add(max(interval.start, usage.start));
                            boundarySet.add(min(interval.end, usage.end));
                        }
                    }
                }
                List<LocalDateTime> boundaries = boundarySet.stream().sorted().toList();
                for (int i = 0; i + 1 < boundaries.size(); i++) {
                    Interval atomic = new Interval(boundaries.get(i), boundaries.get(i + 1));
                    if (!atomic.start.isBefore(atomic.end)) {
                        continue;
                    }
                    boolean available = true;
                    for (ChosenResource resource : chosen) {
                        BigDecimal used = resource.state.usageList.stream().filter(u -> u.overlaps(atomic))
                                .map(u -> u.capacity).reduce(BigDecimal.ZERO, BigDecimal::add);
                        if (used.add(resource.requiredCapacity).compareTo(capacity(resource.state.resource.capacity())) > 0) {
                            available = false;
                            break;
                        }
                    }
                    if (available) {
                        addMerged(result, atomic);
                    }
                }
            }
            return result;
        }

        /**
         * 根据资源排班生成工作区间。班组和设备都只使用明确包含自身的班次。
         * 开启利用资源空档时，仅设备会将同一日首个班次开始到最后一个班次结束合并为连续可用区间；班组不合并。
         * 顶层完全未提供班次快照时保留通用接口的7×24兼容行为；ncloud会为每个自然日传入快照，
         * 因此自动排产中的资源没有排班时仍然严格不可用。
         */
        private List<Interval> workingIntervals(ResourceState state, LocalDateTime lower, LocalDateTime upper) {
            LocalDateTime startBoundary = max(rangeStartTime, lower);
            LocalDateTime endBoundary = min(rangeEndTime, upper);
            if (!startBoundary.isBefore(endBoundary)) {
                return List.of();
            }
            if (request.shiftScheduleList() == null || request.shiftScheduleList().isEmpty()) {
                return List.of(new Interval(startBoundary, endBoundary));
            }
            List<Interval> result = new ArrayList<>();
            for (ApsRequest.ShiftSchedule day : request.shiftScheduleList()) {
                boolean mergeDeviceShiftGaps = state != null && param.useIdleTime()
                        && state.resource.resourceType() == ApsRequest.ResourceType.DEVICE;
                LocalDateTime deviceDayStart = null;
                LocalDateTime deviceDayEnd = null;
                for (ApsRequest.Shift shift : list(day.shiftList())) {
                    if (state != null) {
                        ApsRequest.ResourceType type = state.resource.resourceType();
                        boolean teamUnavailable = type == ApsRequest.ResourceType.TEAM
                                && !list(shift.teamIdList()).contains(state.resource.resourceId());
                        boolean deviceUnavailable = type == ApsRequest.ResourceType.DEVICE
                                && shift.deviceIdList() != null
                                && !list(shift.deviceIdList()).contains(state.resource.resourceId());
                        if (teamUnavailable || deviceUnavailable) {
                            continue;
                        }
                    }
                    LocalDateTime start = day.scheduleDate().atTime(shift.startTime());
                    LocalDateTime end = day.scheduleDate().plusDays(shift.crossDay() ? 1 : 0)
                            .atTime(shift.endTime());
                    if (mergeDeviceShiftGaps) {
                        deviceDayStart = deviceDayStart == null ? start : min(deviceDayStart, start);
                        deviceDayEnd = deviceDayEnd == null ? end : max(deviceDayEnd, end);
                        continue;
                    }
                    start = max(start, startBoundary);
                    end = min(end, endBoundary);
                    if (start.isBefore(end)) {
                        result.add(new Interval(start, end));
                    }
                }
                if (mergeDeviceShiftGaps && deviceDayStart != null && deviceDayEnd != null) {
                    LocalDateTime start = max(deviceDayStart, startBoundary);
                    LocalDateTime end = min(deviceDayEnd, endBoundary);
                    if (start.isBefore(end)) {
                        result.add(new Interval(start, end));
                    }
                }
            }
            result.sort(Comparator.comparing(i -> i.start));
            return merge(result);
        }

        private static boolean isPieceTransfer(ApsRequest.Step step) {
            return step != null && step.attributes() != null && "PIECE".equals(step.attributes().get("transferMode"));
        }

        private static ApsRequest.Step routeStep(ApsRequest.Route route, String id) {
            return route.stepList().stream().filter(step -> Objects.equals(step.stepId(), id)).findFirst().orElse(null);
        }

        /** 工时字段均为单件值；不足一件的剩余批次完成全部工时后流转。 */
        private static int firstPieceSeconds(int totalSeconds, BigDecimal quantity) {
            return Math.min(totalSeconds, BigDecimal.valueOf(totalSeconds)
                    .divide(quantity.max(BigDecimal.ONE), 0, RoundingMode.CEILING).intValueExact());
        }

        /** 首件时间沿有效工作段推进，不能将休息时间当作加工时间。 */
        private static LocalDateTime completionAfter(List<Interval> segments, int seconds) {
            long remaining = seconds;
            for (Interval segment : segments) {
                long length = Duration.between(segment.start, segment.end).getSeconds();
                if (remaining <= length) return segment.start.plusSeconds(remaining);
                remaining -= length;
            }
            return segments.get(segments.size() - 1).end;
        }

        private static LocalDateTime releaseTime(ApsResult.StepResult result, ApsRequest.Step step, BigDecimal quantity) {
            if (!isPieceTransfer(step)) return result.planEndTime();
            List<Interval> segments = result.workSegmentList().stream()
                    .map(segment -> new Interval(segment.startTime(), segment.endTime())).toList();
            return completionAfter(segments, firstPieceSeconds(result.prepareCost() + result.processCost(), quantity));
        }

        /** 倒排单件流转：约束首件完成时间，整批仍必须在任务交期与资源工作段内结束。 */
        private List<Interval> allocateBackwardSinglePiece(List<Interval> available, LocalDateTime batchDeadline,
                LocalDateTime firstDeadline, int totalSeconds, int firstSeconds, boolean continuous) {
            List<Interval> bounded = available.stream().filter(segment -> segment.start.isBefore(batchDeadline))
                    .map(segment -> new Interval(segment.start, min(segment.end, batchDeadline))).toList();
            if (continuous) {
                for (int i = bounded.size() - 1; i >= 0; i--) {
                    Interval segment = bounded.get(i);
                    LocalDateTime start = min(segment.end.minusSeconds(totalSeconds), firstDeadline.minusSeconds(firstSeconds));
                    if (!start.isBefore(segment.start)) return List.of(new Interval(start, start.plusSeconds(totalSeconds)));
                }
                return List.of();
            }
            List<Interval> latest = allocateBackward(bounded, batchDeadline, totalSeconds, false);
            if (latest.isEmpty()) return latest;
            if (!completionAfter(latest, firstSeconds).isAfter(firstDeadline)) return latest;
            List<Interval> prefix = allocateBackward(bounded, firstDeadline, firstSeconds, false);
            if (prefix.isEmpty()) return List.of();
            int remaining = totalSeconds - firstSeconds;
            if (remaining == 0) return prefix;
            List<Interval> tail = allocateForward(bounded, prefix.get(prefix.size() - 1).end, remaining, false);
            if (tail.isEmpty()) return List.of();
            List<Interval> result = new ArrayList<>(prefix);
            result.addAll(tail);
            return result;
        }

        /** 从边界向未来消耗有效工时；非连续工序可以跨多个工作段。 */
        private List<Interval> allocateForward(List<Interval> available, LocalDateTime boundary,
                int seconds, boolean continuous) {
            List<Interval> result = new ArrayList<>();
            if (continuous) {
                for (Interval interval : available) {
                    LocalDateTime start = max(interval.start, boundary);
                    if (!start.isBefore(interval.end)) {
                        continue;
                    }
                    if (Duration.between(start, interval.end).getSeconds() >= seconds) {
                        return List.of(new Interval(start, start.plusSeconds(seconds)));
                    }
                }
                return List.of();
            }
            long remaining = seconds;
            for (Interval interval : available) {
                LocalDateTime start = max(interval.start, boundary);
                if (!start.isBefore(interval.end)) {
                    continue;
                }
                long availableSeconds = Duration.between(start, interval.end).getSeconds();
                long used = Math.min(remaining, availableSeconds);
                if (used > 0) {
                    result.add(new Interval(start, start.plusSeconds(used)));
                }
                remaining -= used;
                if (remaining == 0) {
                    return result;
                }
            }
            return List.of();
        }

        /** 从边界向过去反向消耗有效工时，最终仍按时间正序返回工作段。 */
        private List<Interval> allocateBackward(List<Interval> available, LocalDateTime boundary,
                int seconds, boolean continuous) {
            if (continuous) {
                for (int i = available.size() - 1; i >= 0; i--) {
                    Interval interval = available.get(i);
                    LocalDateTime end = min(interval.end, boundary);
                    if (!interval.start.isBefore(end)) {
                        continue;
                    }
                    if (Duration.between(interval.start, end).getSeconds() >= seconds) {
                        return List.of(new Interval(end.minusSeconds(seconds), end));
                    }
                }
                return List.of();
            }
            long remaining = seconds;
            List<Interval> reverse = new ArrayList<>();
            for (int i = available.size() - 1; i >= 0; i--) {
                Interval interval = available.get(i);
                LocalDateTime end = min(interval.end, boundary);
                if (!interval.start.isBefore(end)) {
                    continue;
                }
                long availableSeconds = Duration.between(interval.start, end).getSeconds();
                long used = Math.min(remaining, availableSeconds);
                if (used > 0) {
                    reverse.add(new Interval(end.minusSeconds(used), end));
                }
                remaining -= used;
                if (remaining == 0) {
                    java.util.Collections.reverse(reverse);
                    return reverse;
                }
            }
            return List.of();
        }

        /** 使用稳定的拓扑排序确定路线顺序；同层工序按 stepId 排序以保证结果可复现。 */
        private List<ApsRequest.Step> topological(ApsRequest.Route route) {
            Map<String, ApsRequest.Step> stepMap = new LinkedHashMap<>();
            for (ApsRequest.Step step : route.stepList()) {
                stepMap.put(step.stepId(), step);
            }
            Map<String, Integer> indegree = new HashMap<>();
            Map<String, List<String>> next = new HashMap<>();
            stepMap.keySet().forEach(id -> indegree.put(id, 0));
            for (ApsRequest.StepEdge edge : list(route.stepEdgeList())) {
                next.computeIfAbsent(edge.startStepId(), k -> new ArrayList<>()).add(edge.endStepId());
                indegree.compute(edge.endStepId(), (k, v) -> v + 1);
            }
            Deque<String> queue = new ArrayDeque<>();
            stepMap.keySet().stream().filter(id -> indegree.get(id) == 0).sorted().forEach(queue::add);
            List<ApsRequest.Step> result = new ArrayList<>();
            while (!queue.isEmpty()) {
                String id = queue.removeFirst();
                result.add(stepMap.get(id));
                List<String> targets = new ArrayList<>(next.getOrDefault(id, List.of()));
                targets.sort(String::compareTo);
                for (String target : targets) {
                    if (indegree.compute(target, (k, v) -> v - 1) == 0) {
                        queue.add(target);
                    }
                }
            }
            return result;
        }

        /** 撤销当前失败工单产生的内部资源占用。 */
        private void rollback(List<ReservedUsage> reservations) {
            for (ReservedUsage reservation : reservations) {
                reservation.state.usageList.remove(reservation.usage);
            }
        }

        /** 在安全边界检查调用方取消信号和本次请求的超时时间。 */
        private void checkControl() {
            if (cancellation.isCancellationRequested()) {
                throw new ControlException(ApsResult.Status.CANCELLED, "排产已取消");
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw new ControlException(ApsResult.Status.TIMEOUT, "排产计算超时");
            }
        }

        /** @return 当前请求是否选择 FORWARD 算法 */
        private boolean isForward() {
            return FORWARD_ALGORITHM_ID.equals(request.algorithmId());
        }
    }

    /** 资源唯一键；不同类型允许使用相同业务 ID。 */
    private record ResourceKey(ApsRequest.ResourceType type, String id) {}

    /** 由一条待排产计划拆出的内部工单。 */
    private record SplitTask(ApsRequest.Task source, String splitTaskId,
                             int splitNo, int splitCount, BigDecimal planNum) {}

    /** 左闭右开的内部时间区间。 */
    private record Interval(LocalDateTime start, LocalDateTime end) {
        boolean overlaps(Interval other) { return start.isBefore(other.end) && end.isAfter(other.start); }
    }
    /** 一段资源容量占用；external=true 表示由调用方输入的历史占用。 */
    private record Usage(LocalDateTime start, LocalDateTime end, BigDecimal capacity, boolean external) {
        boolean overlaps(Interval other) { return start.isBefore(other.end) && end.isAfter(other.start); }
    }
    /** 单次排产中某个资源的静态信息和动态占用列表。 */
    private static final class ResourceState {
        final ApsRequest.Resource resource;
        final List<Usage> usageList = new ArrayList<>();
        ResourceState(ApsRequest.Resource resource) { this.resource = resource; }
    }
    /** 候选组合中已经解析到具体资源状态的一项资源。 */
    private record ChosenResource(ApsRequest.StepResource option, ResourceState state, BigDecimal requiredCapacity) {}

    /** 当前工单产生的可回滚资源占用。 */
    private record ReservedUsage(ResourceState state, Usage usage) {}

    /** 工时计算结果，全部时长单位均为秒。 */
    private record DurationInfo(int prepareSeconds, int processSeconds, int totalSeconds, boolean defaultDuration) {}

    /** 某道工序在一个资源组合上的完整可行安排。 */
    private record Placement(LocalDateTime start, LocalDateTime end, List<Interval> segments,
                             List<ChosenResource> chosenResources, int prepareSeconds,
                             int processSeconds, boolean defaultDuration, int priority, int preferenceRank) {}

    /** 遗传算法染色体：工单加工顺序基因 + 每道工序的资源偏好基因。 */
    private static final class Chromosome {
        private final List<String> operationOrder;
        private final Map<String, Integer> resourceRanks;
        private EvaluatedResult evaluation;

        private Chromosome(List<String> operationOrder, Map<String, Integer> resourceRanks) {
            this.operationOrder = new ArrayList<>(operationOrder);
            this.resourceRanks = new HashMap<>(resourceRanks);
        }

        private int operationRank(String operationId) {
            int value = operationOrder.indexOf(operationId);
            return value < 0 ? Integer.MAX_VALUE : value;
        }

        private int resourceRank(String resourceId) {
            return resourceRanks.getOrDefault(resourceId, Integer.MAX_VALUE / 2);
        }

        private Chromosome copyWithoutResult() {
            return new Chromosome(operationOrder, resourceRanks);
        }
    }

    /** 用于将取消和超时从深层循环快速传递到公共入口的内部控制异常。 */
    private static final class ControlException extends RuntimeException {
        final ApsResult.Status status;
        ControlException(ApsResult.Status status, String message) { super(message); this.status = status; }
    }

    /** 计算两个有序时间区间列表的交集。 */
    private static List<Interval> intersect(List<Interval> left, List<Interval> right) {
        List<Interval> result = new ArrayList<>();
        int i = 0, j = 0;
        while (i < left.size() && j < right.size()) {
            LocalDateTime start = max(left.get(i).start, right.get(j).start);
            LocalDateTime end = min(left.get(i).end, right.get(j).end);
            if (start.isBefore(end)) {
                result.add(new Interval(start, end));
            }
            if (left.get(i).end.isBefore(right.get(j).end)) {
                i++;
            } else {
                j++;
            }
        }
        return result;
    }

    /** 合并重叠或首尾相接的时间区间。 */
    private static List<Interval> merge(List<Interval> source) {
        List<Interval> result = new ArrayList<>();
        source.stream().sorted(Comparator.comparing(i -> i.start)).forEach(i -> addMerged(result, i));
        return result;
    }

    private static void addMerged(List<Interval> target, Interval interval) {
        if (target.isEmpty()) {
            target.add(interval);
            return;
        }
        Interval last = target.get(target.size() - 1);
        if (!interval.start.isAfter(last.end)) {
            target.set(target.size() - 1, new Interval(last.start, max(last.end, interval.end)));
        } else {
            target.add(interval);
        }
    }

    private static int positive(Integer value) { return value == null ? 0 : Math.max(0, value); }
    private static BigDecimal capacity(BigDecimal value) {
        return value == null || value.signum() <= 0 ? BigDecimal.ONE : value;
    }
    private static BigDecimal orOne(BigDecimal value) {
        return value == null || value.signum() <= 0 ? BigDecimal.ONE : value;
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void required(String value, String field, List<ApsResult.Issue> issues) {
        if (blank(value)) {
            issues.add(issue(ERROR, "APS-INPUT-REQUIRED", field + "不能为空", "REQUEST", null));
        }
    }
    private static String safeMessage(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
    private static ApsResult.Issue issue(ApsResult.Severity severity, String code, String message,
            String objectType, String objectId) {
        return new ApsResult.Issue(severity, code, message, objectType, objectId, Map.of());
    }
    private static <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
    private static LocalDateTime max(LocalDateTime first, LocalDateTime... others) {
        LocalDateTime result = first;
        for (LocalDateTime value : others) {
            if (value != null && value.isAfter(result)) {
                result = value;
            }
        }
        return result;
    }
    private static LocalDateTime min(LocalDateTime first, LocalDateTime... others) {
        LocalDateTime result = first;
        for (LocalDateTime value : others) {
            if (value != null && value.isBefore(result)) {
                result = value;
            }
        }
        return result;
    }
}
