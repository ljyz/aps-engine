package com.smartmes.aps.core;

import com.smartmes.aps.api.ApsEngine;
import com.smartmes.aps.api.ApsEngines;
import com.smartmes.aps.api.ApsRequest;
import com.smartmes.aps.api.ApsResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultApsEngineTest {
    private final ApsEngine engine = ApsEngines.createDefault();

    @Test
    void usesEightHoursWhenBothRouteDurationFieldsAreMissing() {
        LocalDateTime start = at(2026, 9, 1, 0, 0);
        ApsRequest request = request(DefaultApsEngine.FORWARD_ALGORITHM_ID, start, at(2026, 9, 2, 0, 0),
                List.of(), List.of(), route(List.of(step("S1", null, null, false, List.of())), List.of()));

        ApsResult result = engine.schedule(request);

        ApsResult.StepResult scheduled = result.bestSolution().taskList().get(0).stepList().get(0);
        assertEquals(ApsResult.Status.SUCCESS, result.status());
        assertEquals(start.plusHours(8), scheduled.planEndTime());
        assertEquals(8 * 60 * 60, scheduled.processCost());
        assertTrue(scheduled.defaultDurationApplied());
    }

    @Test
    void derivesAvailabilityFromShiftsAndSubtractsResourceOccupancy() {
        LocalDateTime start = at(2026, 9, 1, 8, 0);
        ApsRequest.Resource device = new ApsRequest.Resource(ApsRequest.ResourceType.DEVICE, "D1", "D1", "设备1",
                BigDecimal.ONE,
                List.of(new ApsRequest.ResourceOccupancy("O1", at(2026, 9, 1, 10, 0), at(2026, 9, 1, 12, 0),
                        BigDecimal.ONE, "EXISTING_PLAN", "OLD-1", null)), Map.of());
        ApsRequest.StepResource candidate = new ApsRequest.StepResource(ApsRequest.ResourceType.DEVICE, "D1",
                1, true, null, null, null, null, BigDecimal.ONE, true, false, Map.of());
        List<ApsRequest.ShiftSchedule> shifts = List.of(
                shiftDay(LocalDate.of(2026, 9, 1)), shiftDay(LocalDate.of(2026, 9, 2)));
        ApsRequest request = request(DefaultApsEngine.FORWARD_ALGORITHM_ID, start, at(2026, 9, 2, 17, 0),
                shifts, List.of(device), route(List.of(step("S1", 0, 480, true, List.of(candidate))), List.of()));

        ApsResult.Solution solution = engine.schedule(request).bestSolution();

        assertEquals(at(2026, 9, 2, 9, 0), solution.itemList().get(0).planEndTime());
        assertEquals("D1", solution.itemList().get(0).deviceId());
        assertEquals(solution.cardList().get(0).id(), solution.itemList().get(0).cardId());
    }

    @Test
    void intersectsExplicitDeviceAndTeamSchedules() {
        LocalDate date = LocalDate.of(2026, 9, 1);
        LocalDateTime start = date.atTime(8, 0);
        ApsRequest.Resource device = new ApsRequest.Resource(ApsRequest.ResourceType.DEVICE,
                "D1", "D1", "设备1", BigDecimal.ONE, List.of(), Map.of());
        ApsRequest.Resource team = new ApsRequest.Resource(ApsRequest.ResourceType.TEAM,
                "T1", "T1", "班组1", BigDecimal.ONE, List.of(), Map.of());
        List<ApsRequest.ShiftSchedule> shifts = List.of(new ApsRequest.ShiftSchedule(date, List.of(
                new ApsRequest.Shift("DAY", "DAY", "白班", LocalTime.of(8, 0),
                        LocalTime.of(17, 0), false, 540, List.of("T1"), List.of("D1")))));
        List<ApsRequest.StepResource> resources = List.of(
                new ApsRequest.StepResource(ApsRequest.ResourceType.DEVICE, "D1", 1,
                        true, null, null, null, null, BigDecimal.ONE, true, false, Map.of()),
                new ApsRequest.StepResource(ApsRequest.ResourceType.TEAM, "T1", 1,
                        true, null, null, null, null, BigDecimal.ONE, true, false, Map.of()));
        ApsRequest request = request(DefaultApsEngine.FORWARD_ALGORITHM_ID, start, date.atTime(17, 0),
                shifts, List.of(device, team), route(List.of(step("S1", 0, 60, true, resources)), List.of()));

        assertEquals(ApsResult.Status.SUCCESS, engine.schedule(request).status());

        ApsRequest.ShiftSchedule noDeviceSchedule = new ApsRequest.ShiftSchedule(date, List.of(
                new ApsRequest.Shift("DAY", "DAY", "白班", LocalTime.of(8, 0),
                        LocalTime.of(17, 0), false, 540, List.of("T1"), List.of())));
        ApsRequest unavailable = request(DefaultApsEngine.FORWARD_ALGORITHM_ID, start, date.atTime(17, 0),
                List.of(noDeviceSchedule), List.of(device, team),
                route(List.of(step("S1", 0, 60, true, resources)), List.of()));
        ApsResult unavailableResult = engine.schedule(unavailable);
        assertEquals(ApsResult.Status.INFEASIBLE, unavailableResult.status());
        assertNull(unavailableResult.bestSolution());
    }

    @Test
    void backwardPlanRespectsRouteOrderAndMinimumInterval() {
        LocalDateTime start = at(2026, 9, 1, 0, 0);
        LocalDateTime end = at(2026, 9, 1, 18, 0);
        ApsRequest.StepEdge edge = new ApsRequest.StepEdge("S1", "S2", 30, null);
        ApsRequest request = request(DefaultApsEngine.BACKWARD_ALGORITHM_ID, start, end, List.of(), List.of(),
                route(List.of(step("S1", 0, 60, false, List.of()),
                        step("S2", 0, 120, false, List.of())), List.of(edge)));

        ApsResult.TaskResult task = engine.schedule(request).bestSolution().taskList().get(0);

        assertEquals(at(2026, 9, 1, 14, 30), task.planStartTime());
        assertEquals(at(2026, 9, 1, 16, 0), task.stepList().get(1).planStartTime());
        assertEquals(end, task.planEndTime());
    }

    @Test
    void forwardDeadlineIsSoftConstraintAndStillPenalizesLateness() {
        LocalDateTime start = at(2026, 9, 1, 8, 0);
        ApsRequest request = request(DefaultApsEngine.FORWARD_ALGORITHM_ID, start,
                at(2026, 9, 1, 9, 0), List.of(), List.of(),
                route(List.of(step("S1", 0, 120, false, List.of())), List.of()));

        ApsResult result = engine.schedule(request);
        ApsResult.TaskResult task = result.bestSolution().taskList().get(0);

        assertEquals(ApsResult.Status.SUCCESS, result.status());
        assertEquals(at(2026, 9, 1, 10, 0), task.planEndTime());
        assertEquals(60, task.latenessMinutes());
        assertTrue(result.bestSolution().score().compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(result.bestSolution().score().compareTo(BigDecimal.valueOf(100)) < 0);
    }

    @Test
    void backwardOnlyFallsBackToEarliestLateScheduleWhenEnabled() {
        LocalDateTime start = at(2026, 9, 1, 8, 0);
        ApsRequest base = request(DefaultApsEngine.BACKWARD_ALGORITHM_ID, start,
                at(2026, 9, 1, 9, 0), List.of(shiftDay(start.toLocalDate())), List.of(),
                route(List.of(step("S1", 0, 120, false, List.of())), List.of()));

        ApsResult strict = engine.schedule(withLateCompletion(base, false, 1));
        ApsResult fallback = engine.schedule(withLateCompletion(base, true, 1));

        assertEquals(ApsResult.Status.INFEASIBLE, strict.status());
        assertEquals(ApsResult.Status.SUCCESS, fallback.status());
        assertEquals(at(2026, 9, 1, 8, 0), fallback.bestSolution().planStartTime());
        assertEquals(at(2026, 9, 1, 10, 0), fallback.bestSolution().planEndTime());
        assertEquals(60, fallback.bestSolution().taskList().get(0).latenessMinutes());
    }

    @Test
    void exposesBothSupportedSolversAndDirections() {
        assertEquals(2, engine.getSupportedAlgorithms().size());
        assertTrue(engine.getSupportedAlgorithms().stream()
                .anyMatch(item -> DefaultApsEngine.HEURISTIC_SOLVER_ID.equals(item.algorithmId())));
        assertTrue(engine.getSupportedAlgorithms().stream()
                .anyMatch(item -> DefaultApsEngine.GENETIC_SOLVER_ID.equals(item.algorithmId())));
        assertEquals(java.util.Set.of(DefaultApsEngine.FORWARD_ALGORITHM_ID,
                DefaultApsEngine.BACKWARD_ALGORITHM_ID), engine.getEngineInfo().supportedDirectionList());
    }

    @Test
    void splitsPlanAndProducesNcloudItemList() {
        LocalDateTime start = at(2026, 9, 1, 8, 0);
        ApsRequest.Route route = route(List.of(step("S1", 0, 60, false, List.of())), List.of());
        ApsRequest.Task plan = new ApsRequest.Task("PLAN-1", null, "PLAN-001", null, route.routeId(), route,
                "SKU-1", "SKU-1", new BigDecimal("25"), new BigDecimal("10"),
                1, false, start, at(2026, 9, 3, 18, 0), Map.of());
        ApsRequest request = new ApsRequest("1.0", "REQ-SPLIT", ApsRequest.SolverType.HEURISTIC,
                DefaultApsEngine.FORWARD_ALGORITHM_ID,
                start, 1L, ApsRequest.AlgorithmParam.defaults(), List.of(), List.of(),
                List.of(plan), ApsRequest.OutputParam.defaults());

        ApsResult result = engine.schedule(request);

        assertEquals(ApsResult.Status.SUCCESS, result.status());
        assertEquals(3, result.bestSolution().cardList().size());
        assertEquals(3, result.bestSolution().itemList().size());
        assertEquals(List.of(new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("5")),
                result.bestSolution().itemList().stream().map(ApsResult.SchedulePlanItem::planNum).toList());
        assertEquals("PLAN-1", result.bestSolution().itemList().get(0).workshopPlanId());
        assertEquals("0", result.bestSolution().itemList().get(0).planStatus());
    }

    @Test
    void appendModeControlsExistingOccupancyBehavior() {
        LocalDateTime start = at(2026, 9, 1, 8, 0);
        ApsRequest.Resource device = new ApsRequest.Resource(ApsRequest.ResourceType.DEVICE, "D1", "D1", "设备1",
                BigDecimal.ONE, List.of(new ApsRequest.ResourceOccupancy("O1", at(2026, 9, 1, 8, 0),
                at(2026, 9, 1, 12, 0), BigDecimal.ONE, "PLAN", "1", null)), Map.of());
        ApsRequest.StepResource candidate = new ApsRequest.StepResource(ApsRequest.ResourceType.DEVICE, "D1",
                1, true, null, null, null, null, BigDecimal.ONE, true, true, Map.of());
        ApsRequest request = request(DefaultApsEngine.FORWARD_ALGORITHM_ID, start, at(2026, 9, 1, 18, 0),
                List.of(), List.of(device), route(List.of(step("S1", 0, 60, true, List.of(candidate))), List.of()));

        ApsResult avoidExisting = engine.schedule(withParam(request, false, true));
        ApsResult ignoreExisting = engine.schedule(withParam(request, true, false));

        assertEquals(at(2026, 9, 1, 12, 0), avoidExisting.bestSolution().planStartTime());
        assertEquals(start, ignoreExisting.bestSolution().planStartTime());
    }

    @Test
    void idleTimeMergesShiftGapsForDeviceButNotTeam() {
        LocalDate date = LocalDate.of(2026, 9, 1);
        LocalDateTime start = date.atTime(8, 0);
        ApsRequest.Resource device = new ApsRequest.Resource(ApsRequest.ResourceType.DEVICE,
                "D1", "D1", "设备1", BigDecimal.ONE, List.of(), Map.of());
        ApsRequest.Resource team = new ApsRequest.Resource(ApsRequest.ResourceType.TEAM,
                "T1", "T1", "班组1", BigDecimal.ONE, List.of(), Map.of());
        List<ApsRequest.ShiftSchedule> shifts = List.of(new ApsRequest.ShiftSchedule(date, List.of(
                new ApsRequest.Shift("S1", "S1", "早班", LocalTime.of(8, 0),
                        LocalTime.of(12, 0), false, 240, List.of("T1"), List.of("D1")),
                new ApsRequest.Shift("S2", "S2", "下午班", LocalTime.of(13, 0),
                        LocalTime.of(17, 0), false, 240, List.of("T1"), List.of("D1")))));
        ApsRequest.StepResource deviceCandidate = new ApsRequest.StepResource(
                ApsRequest.ResourceType.DEVICE, "D1", 1, true,
                null, null, null, null, BigDecimal.ONE, true, false, Map.of());
        ApsRequest.StepResource teamCandidate = new ApsRequest.StepResource(
                ApsRequest.ResourceType.TEAM, "T1", 1, true,
                null, null, null, null, BigDecimal.ONE, true, false, Map.of());
        ApsRequest deviceRequest = request(DefaultApsEngine.FORWARD_ALGORITHM_ID, start, date.atTime(17, 0),
                shifts, List.of(device), route(List.of(step("S1", 0, 300, true,
                        List.of(deviceCandidate))), List.of()));
        ApsRequest teamRequest = request(DefaultApsEngine.FORWARD_ALGORITHM_ID, start, date.atTime(17, 0),
                shifts, List.of(team), route(List.of(step("S1", 0, 300, false,
                        List.of(teamCandidate))), List.of()));

        ApsResult deviceResult = engine.schedule(withParam(deviceRequest, true, true));
        ApsResult teamResult = engine.schedule(withParam(teamRequest, true, true));

        assertEquals(date.atTime(13, 0), deviceResult.bestSolution().planEndTime());
        assertEquals(date.atTime(14, 0), teamResult.bestSolution().planEndTime());
    }

    @Test
    void takeTimeDeterminesDurationWithoutShiftOutput() {
        LocalDateTime start = at(2026, 9, 1, 8, 0);
        ApsRequest.Resource device = new ApsRequest.Resource(ApsRequest.ResourceType.DEVICE, "D1", "D1", "设备1",
                BigDecimal.ONE, List.of(), Map.of());
        ApsRequest.StepResource candidate = new ApsRequest.StepResource(ApsRequest.ResourceType.DEVICE, "D1",
                1, true, 30, null, null, null, BigDecimal.ONE, true, true, Map.of());
        ApsRequest request = request(DefaultApsEngine.FORWARD_ALGORITHM_ID, start, at(2026, 9, 1, 17, 0),
                List.of(shiftDay(start.toLocalDate())), List.of(device),
                route(List.of(step("S1", null, null, true, List.of(candidate))), List.of()));

        ApsResult.StepResult result = engine.schedule(request).bestSolution().taskList().get(0).stepList().get(0);

        assertEquals(30, result.processCost());
        assertEquals(at(2026, 9, 1, 8, 0).plusSeconds(30), result.planEndTime());
    }

    @Test
    void keepsSecondPrecisionForRouteDuration() {
        LocalDateTime start = at(2026, 9, 1, 8, 0);
        ApsRequest.Step step = new ApsRequest.Step("S1", "S1", "S1", "S1", false,
                false, 30, 45, List.of(), Map.of());
        ApsRequest request = request(DefaultApsEngine.FORWARD_ALGORITHM_ID, start,
                at(2026, 9, 1, 17, 0), List.of(), List.of(), route(List.of(step), List.of()));

        ApsResult.StepResult result = engine.schedule(request).bestSolution()
                .taskList().get(0).stepList().get(0);

        assertEquals(30, result.prepareCost());
        assertEquals(45, result.processCost());
        assertEquals(start.plusSeconds(75), result.planEndTime());
    }

    @Test
    void multipliesUnitRouteDurationsBySplitOrderQuantity() {
        LocalDateTime start = at(2026, 9, 1, 8, 0);
        ApsRequest.Step unitStep = new ApsRequest.Step("S1", "S1", "S1", "S1", false,
                false, 30, 45, List.of(), Map.of());
        ApsRequest.Route route = route(List.of(unitStep), List.of());
        ApsRequest.Task task = new ApsRequest.Task("T1", null, "WO-001", null, route.routeId(), route,
                "SKU-1", "SKU-1", BigDecimal.TEN, BigDecimal.TEN,
                1, false, start, at(2026, 9, 1, 17, 0), Map.of());
        ApsRequest base = request(DefaultApsEngine.FORWARD_ALGORITHM_ID, start,
                at(2026, 9, 1, 17, 0), List.of(), List.of(), route);
        ApsRequest input = new ApsRequest(base.schemaVersion(), base.requestId(), base.solverType(),
                base.algorithmId(), base.currentTime(), base.randomSeed(), base.algorithmParam(),
                base.shiftScheduleList(), base.resourceList(), List.of(task), base.outputParam());

        ApsResult.StepResult result = engine.schedule(input).bestSolution().taskList().get(0).stepList().get(0);

        assertEquals(300, result.prepareCost());
        assertEquals(450, result.processCost());
        assertEquals(start.plusSeconds(750), result.planEndTime());
    }

    @Test
    void usesUnitRhythmAndChangeoverInsteadOfRouteDurations() {
        LocalDateTime start = at(2026, 9, 1, 8, 0);
        ApsRequest.Resource device = new ApsRequest.Resource(ApsRequest.ResourceType.DEVICE, "D1", "D1", "设备1",
                BigDecimal.ONE, List.of(), Map.of());
        ApsRequest.StepResource candidate = new ApsRequest.StepResource(ApsRequest.ResourceType.DEVICE, "D1",
                1, true, 30, new BigDecimal("1.5"), 2, null, BigDecimal.ONE, true, true, Map.of());
        ApsRequest.Step unitStep = new ApsRequest.Step("S1", "S1", "S1", "S1", false,
                true, 20, 40, List.of(candidate), Map.of());
        ApsRequest.Route route = route(List.of(unitStep), List.of());
        ApsRequest.Task task = new ApsRequest.Task("T1", null, "WO-001", null, route.routeId(), route,
                "SKU-1", "SKU-1", BigDecimal.TEN, BigDecimal.TEN,
                1, false, start, at(2026, 9, 1, 17, 0), Map.of());
        ApsRequest base = request(DefaultApsEngine.FORWARD_ALGORITHM_ID, start,
                at(2026, 9, 1, 17, 0), List.of(shiftDay(start.toLocalDate())), List.of(device), route);
        ApsRequest input = new ApsRequest(base.schemaVersion(), base.requestId(), base.solverType(),
                base.algorithmId(), base.currentTime(), base.randomSeed(), base.algorithmParam(),
                base.shiftScheduleList(), base.resourceList(), List.of(task), base.outputParam());

        ApsResult.StepResult result = engine.schedule(input).bestSolution().taskList().get(0).stepList().get(0);

        assertEquals(1200, result.prepareCost());
        assertEquals(450, result.processCost());
        assertEquals(start.plusSeconds(1650), result.planEndTime());
    }

    @Test
    void maximumProcessIntervalRejectsShiftGap() {
        LocalDateTime start = at(2026, 9, 1, 8, 0);
        ApsRequest.StepEdge edge = new ApsRequest.StepEdge("S1", "S2", 0, 60);
        ApsRequest request = request(DefaultApsEngine.FORWARD_ALGORITHM_ID, start, at(2026, 9, 2, 17, 0),
                List.of(shiftDay(LocalDate.of(2026, 9, 1)), shiftDay(LocalDate.of(2026, 9, 2))), List.of(),
                route(List.of(step("S1", 0, 540, false, List.of()),
                        step("S2", 0, 60, false, List.of())), List.of(edge)));

        assertEquals(ApsResult.Status.INFEASIBLE, engine.schedule(request).status());
    }

    @Test
    void populationAndOutputOptionsReturnRankedCandidatesAndDigest() {
        LocalDateTime start = at(2026, 9, 1, 8, 0);
        ApsRequest base = request(DefaultApsEngine.FORWARD_ALGORITHM_ID, start,
                at(2026, 9, 2, 8, 0), List.of(), List.of(),
                route(List.of(step("S1", 0, 60, false, List.of())), List.of()));
        ApsRequest.AlgorithmParam defaults = ApsRequest.AlgorithmParam.defaults();
        ApsRequest.AlgorithmParam param = new ApsRequest.AlgorithmParam(true, true, false,
                defaults.lateCompletionLimit(), false, 0, 0,
                1, 1, 1, 2, Duration.ofMinutes(1), BigDecimal.ONE,
                new ApsRequest.HeuristicParam(3, 256, true), defaults.geneticParam());
        ApsRequest request = new ApsRequest(base.schemaVersion(), base.requestId(), ApsRequest.SolverType.HEURISTIC,
                base.algorithmId(),
                base.currentTime(), 7L, param, base.shiftScheduleList(), base.resourceList(), base.taskList(),
                new ApsRequest.OutputParam(2, true, true, true, true));

        ApsResult result = engine.schedule(request);

        assertEquals(1, result.candidateSolutionList().size());
        assertEquals(result.bestSolution().solutionId(), result.candidateSolutionList().get(0).solutionId());
        assertEquals(1, result.inputDigest().taskCount());
        assertEquals(1, result.inputDigest().stepCount());
    }

    @Test
    void geneticSolverUsesSameFiniteCapacityDecoder() {
        LocalDateTime start = at(2026, 9, 1, 8, 0);
        ApsRequest base = request(DefaultApsEngine.FORWARD_ALGORITHM_ID, start,
                at(2026, 9, 2, 8, 0), List.of(), List.of(),
                route(List.of(step("S1", 0, 60, false, List.of())), List.of()));
        ApsRequest.AlgorithmParam defaults = ApsRequest.AlgorithmParam.defaults();
        ApsRequest.AlgorithmParam param = new ApsRequest.AlgorithmParam(
                defaults.useIdleTime(), defaults.appendMode(), defaults.allowLateCompletion(),
                defaults.lateCompletionLimit(), defaults.enableToolingConstraint(),
                defaults.minProcessIntervalMinutes(), defaults.maxProcessIntervalHours(),
                defaults.finishTimeWeight(), defaults.achievementRateWeight(), defaults.onTimeDeliveryWeight(),
                1, Duration.ofMinutes(1), defaults.globalTimeScale(), defaults.heuristicParam(),
                new ApsRequest.GeneticParam(4, 3, new BigDecimal("0.8"), new BigDecimal("0.2"),
                        1, 2, 2, new BigDecimal("0.5")));
        ApsRequest request = new ApsRequest(base.schemaVersion(), base.requestId(), ApsRequest.SolverType.GENETIC,
                base.algorithmId(), base.currentTime(), 9L, param, base.shiftScheduleList(), base.resourceList(),
                base.taskList(), new ApsRequest.OutputParam(2, true, true, true, true));

        ApsResult result = engine.schedule(request);

        assertEquals(ApsResult.Status.SUCCESS, result.status());
        assertEquals(ApsRequest.SolverType.GENETIC, result.solverType());
        assertEquals(1, result.candidateSolutionList().size());
    }

    private static ApsRequest withParam(ApsRequest request, boolean useIdle, boolean append) {
        ApsRequest.AlgorithmParam defaults = ApsRequest.AlgorithmParam.defaults();
        ApsRequest.AlgorithmParam param = new ApsRequest.AlgorithmParam(useIdle,
                append, defaults.allowLateCompletion(), defaults.lateCompletionLimit(),
                defaults.enableToolingConstraint(),
                defaults.minProcessIntervalMinutes(), defaults.maxProcessIntervalHours(), defaults.finishTimeWeight(),
                defaults.achievementRateWeight(), defaults.onTimeDeliveryWeight(), defaults.maxParallelism(),
                Duration.ofMinutes(1), defaults.globalTimeScale(), defaults.heuristicParam(), defaults.geneticParam());
        return new ApsRequest(request.schemaVersion(), request.requestId(), request.solverType(), request.algorithmId(),
                request.currentTime(), request.randomSeed(), param, request.shiftScheduleList(),
                request.resourceList(), request.taskList(), request.outputParam());
    }

    private static ApsRequest withLateCompletion(ApsRequest request, boolean allowLate, int days) {
        ApsRequest.AlgorithmParam defaults = ApsRequest.AlgorithmParam.defaults();
        ApsRequest.AlgorithmParam param = new ApsRequest.AlgorithmParam(
                defaults.useIdleTime(), defaults.appendMode(), allowLate, Duration.ofDays(days),
                defaults.enableToolingConstraint(), defaults.minProcessIntervalMinutes(),
                defaults.maxProcessIntervalHours(), defaults.finishTimeWeight(),
                defaults.achievementRateWeight(), defaults.onTimeDeliveryWeight(),
                defaults.maxParallelism(), Duration.ofMinutes(1), defaults.globalTimeScale(),
                defaults.heuristicParam(), defaults.geneticParam());
        return new ApsRequest(request.schemaVersion(), request.requestId(), request.solverType(),
                request.algorithmId(), request.currentTime(), request.randomSeed(), param,
                request.shiftScheduleList(), request.resourceList(), request.taskList(), request.outputParam());
    }

    private static ApsRequest request(String algorithmId, LocalDateTime start, LocalDateTime end,
            List<ApsRequest.ShiftSchedule> shifts, List<ApsRequest.Resource> resources, ApsRequest.Route route) {
        ApsRequest.Task task = new ApsRequest.Task("T1", null, "WO-001", null, route.routeId(), route,
                "SKU-1", "SKU-1", BigDecimal.ONE, BigDecimal.ONE,
                1, false, start, end, Map.of());
        return new ApsRequest("1.0", "REQ-001", ApsRequest.SolverType.HEURISTIC, algorithmId, start, 1L,
                ApsRequest.AlgorithmParam.defaults(), shifts, resources,
                List.of(task), ApsRequest.OutputParam.defaults());
    }

    private static ApsRequest.Route route(List<ApsRequest.Step> steps, List<ApsRequest.StepEdge> edges) {
        return new ApsRequest.Route("R1", "R1", "测试路线", steps, edges);
    }

    private static ApsRequest.Step step(String id, Integer prepareMinutes, Integer processMinutes, boolean needDevice,
            List<ApsRequest.StepResource> resources) {
        Integer prepareSeconds = prepareMinutes == null ? null : Math.multiplyExact(prepareMinutes, 60);
        Integer processSeconds = processMinutes == null ? null : Math.multiplyExact(processMinutes, 60);
        return new ApsRequest.Step(id, id, id, id, false, needDevice, prepareSeconds, processSeconds,
                resources, Map.of());
    }

    private static ApsRequest.ShiftSchedule shiftDay(LocalDate date) {
        return new ApsRequest.ShiftSchedule(date,
                List.of(new ApsRequest.Shift("SHIFT-1", "DAY", "白班", LocalTime.of(8, 0),
                        LocalTime.of(17, 0), false, 540, List.of())));
    }

    private static LocalDateTime at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute);
    }
}
