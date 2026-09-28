package com.smartmes.aps.core;

import com.smartmes.aps.api.ApsRequest;
import com.smartmes.aps.api.ApsResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/** 按工单、数量、工序资源和实际工作段识别排法，不包含候选ID和临时单号。 */
record ApsCandidateKey(List<TaskKey> tasks) {
    static ApsCandidateKey from(List<ApsResult.TaskResult> tasks) {
        return new ApsCandidateKey(tasks.stream()
                .sorted(Comparator.comparing(ApsResult.TaskResult::taskId)
                        .thenComparing(ApsResult.TaskResult::splitTaskId))
                .map(task -> new TaskKey(task.taskId(), task.splitTaskId(), task.planNum().stripTrailingZeros(),
                        task.stepList().stream().sorted(Comparator.comparing(ApsResult.StepResult::stepId))
                                .map(ApsCandidateKey::stepKey).toList())).toList());
    }

    private static StepKey stepKey(ApsResult.StepResult step) {
        List<ResourceKey> resources = step.resourceList().stream()
                .map(resource -> new ResourceKey(resource.resourceType(), resource.resourceId(),
                        resource.capacityUsed().stripTrailingZeros()))
                .sorted(Comparator.comparing(ResourceKey::type).thenComparing(ResourceKey::id)
                        .thenComparing(ResourceKey::capacity)).toList();
        List<ApsResult.TimeSegment> segments = step.workSegmentList().stream()
                .sorted(Comparator.comparing(ApsResult.TimeSegment::startTime)
                        .thenComparing(ApsResult.TimeSegment::endTime)).toList();
        return new StepKey(step.stepId(), step.planStartTime(), step.planEndTime(), step.prepareCost(),
                step.processCost(), resources, segments);
    }

    record TaskKey(String taskId, String splitTaskId, BigDecimal quantity, List<StepKey> steps) {}
    record StepKey(String stepId, LocalDateTime start, LocalDateTime end, int prepare, int process,
            List<ResourceKey> resources, List<ApsResult.TimeSegment> segments) {}
    record ResourceKey(ApsRequest.ResourceType type, String id, BigDecimal capacity) {}
}
