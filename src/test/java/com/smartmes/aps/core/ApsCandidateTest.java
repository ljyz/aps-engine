package com.smartmes.aps.core;

import com.smartmes.aps.api.ApsRequest;
import com.smartmes.aps.api.ApsResult;
import com.smartmes.aps.api.ApsEngines;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApsCandidateTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 29, 8, 0);

    @Test
    void geneticArchivePreservesDistinctEqualScorePlansAndRecommendsEarlierIndividualCompletion() {
        var result = ApsEngines.createDefault().schedule(request(ApsRequest.SolverType.GENETIC, 7200, true, true, 5));
        assertEquals(ApsResult.Status.SUCCESS, result.status());
        assertEquals(2, result.candidateSolutionList().size());
        assertEquals(START.plusHours(7), result.bestSolution().planEndTime());
        var best = variable(result.bestSolution());
        assertEquals("FAST", best.stepList().get(0).resourceList().get(0).resourceId());
        assertEquals(START.plusHours(1), best.planEndTime());
        var other = result.candidateSolutionList().get(1);
        assertEquals(result.bestSolution().score(), other.score());
        assertEquals(START.plusHours(2), variable(other).planEndTime());
        assertEquals(result.bestSolution(), result.candidateSolutionList().get(0));
    }

    @Test
    void recommendationDoesNotDependOnNumberOfRequestedCandidates() {
        var engine = ApsEngines.createDefault();
        var one = engine.schedule(request(ApsRequest.SolverType.GENETIC, 7200, true, true, 1));
        var many = engine.schedule(request(ApsRequest.SolverType.GENETIC, 7200, true, true, 5));
        assertEquals(variable(many.bestSolution()), variable(one.bestSolution()));
        assertEquals(1, one.candidateSolutionList().size());
    }

    @Test
    void heuristicDeduplicatesWithoutMergingDifferentDevices() {
        var result = ApsEngines.createDefault().schedule(request(ApsRequest.SolverType.HEURISTIC, 3600, true, true, 5));
        assertEquals(2, result.candidateSolutionList().size());
        assertEquals(2, result.candidateSolutionList().stream().map(c ->
                variable(c).stepList().get(0).resourceList().get(0).resourceId()).distinct().count());
    }

    @Test
    void hidingTimelineDoesNotMergeDifferentResourceAssignments() {
        var result = ApsEngines.createDefault().schedule(request(ApsRequest.SolverType.GENETIC, 3600, false, true, 5));
        assertEquals(2, result.candidateSolutionList().size());
        assertTrue(variable(result.bestSolution()).stepList().get(0).resourceList().isEmpty());
        assertEquals(2, result.candidateSolutionList().stream().map(c -> c.itemList().stream()
                .filter(i -> i.workshopPlanId().equals("VAR")).findFirst().orElseThrow().deviceId()).distinct().count());
    }

    @Test
    void outputFlagsDoNotChangeRecommendedSchedule() {
        var engine = ApsEngines.createDefault();
        var normal = engine.schedule(request(ApsRequest.SolverType.GENETIC, 7200, true, true, 5));
        var hidden = engine.schedule(request(ApsRequest.SolverType.GENETIC, 7200, true, false, 5));
        var zero = engine.schedule(request(ApsRequest.SolverType.GENETIC, 7200, true, true, 0));
        assertTrue(hidden.candidateSolutionList().isEmpty());
        assertTrue(zero.candidateSolutionList().isEmpty());
        assertEquals(variable(normal.bestSolution()), variable(hidden.bestSolution()));
        assertEquals(variable(normal.bestSolution()), variable(zero.bestSolution()));
    }

    @Test
    void identicalSchedulesIgnoreListOrderLabelsAndDecimalScale() {
        var first = keyTask("A", "1.0", List.of(assignment("D", "old", "1.0"), assignment("E", "old", "1")),
                List.of(segment(0, 1), segment(2, 3)));
        var same = keyTask("A", "1.00", List.of(assignment("E", "new", "1.00"), assignment("D", "new", "1")),
                List.of(segment(2, 3), segment(0, 1)));
        var second = keyTask("B", "1", List.of(assignment("D", "name", "1")), List.of(segment(0, 1)));
        assertEquals(ApsCandidateKey.from(List.of(first, second)), ApsCandidateKey.from(List.of(second, same)));
    }

    @Test
    void sameOuterTimesWithDifferentWorkingSegmentsRemainDistinct() {
        var first = keyTask("A", "1", List.of(assignment("D", "name", "1")), List.of(segment(0, 1), segment(2, 3)));
        var other = keyTask("A", "1", List.of(assignment("D", "name", "1")), List.of(segment(0, 2), segment(2, 3)));
        assertNotEquals(ApsCandidateKey.from(List.of(first)), ApsCandidateKey.from(List.of(other)));
    }

    @Test
    void differentQuantitiesAndCapacityRemainDistinct() {
        var first = keyTask("A", "1", List.of(assignment("D", "name", "1")), List.of(segment(0, 1)));
        var quantity = keyTask("A", "2", List.of(assignment("D", "name", "1")), List.of(segment(0, 1)));
        var capacity = keyTask("A", "1", List.of(assignment("D", "name", "2")), List.of(segment(0, 1)));
        assertNotEquals(ApsCandidateKey.from(List.of(first)), ApsCandidateKey.from(List.of(quantity)));
        assertNotEquals(ApsCandidateKey.from(List.of(first)), ApsCandidateKey.from(List.of(capacity)));
    }

    private static ApsRequest request(ApsRequest.SolverType solver, int slowSeconds, boolean timeline, boolean candidates, int limit) {
        var options = List.of(option("SLOW", slowSeconds), option("FAST", 3600));
        var variable = task("VAR", 0, options);
        var fixed = task("FIX", 7 * 3600, List.of());
        var defaults = ApsRequest.AlgorithmParam.defaults();
        var param = new ApsRequest.AlgorithmParam(true, true, false, Duration.ofDays(30), false, 0, 0,
                1, 1, 1, 1, Duration.ofMinutes(1), BigDecimal.ONE,
                new ApsRequest.HeuristicParam(20, 256, true),
                new ApsRequest.GeneticParam(30, 20, new BigDecimal("0.8"), new BigDecimal("0.1"), 2, 3, 3,
                        new BigDecimal("0.3")));
        return new ApsRequest("1.0", "CANDIDATES", solver, "FORWARD", START, 1L, param,
                List.of(), List.of(resource("SLOW"), resource("FAST")), List.of(variable, fixed),
                new ApsRequest.OutputParam(limit, candidates, timeline, true, true));
    }

    private static ApsRequest.Task task(String id, int seconds, List<ApsRequest.StepResource> resources) {
        var step = new ApsRequest.Step(id + "S", "S", "S", "加工", true, !resources.isEmpty(), 0, seconds, resources, Map.of());
        var route = new ApsRequest.Route(id + "R", "R", "路线", List.of(step), List.of());
        return new ApsRequest.Task(id, null, id, null, route.routeId(), route, "SKU", "SKU", BigDecimal.ONE, BigDecimal.ONE,
                1, false, START, START.plusDays(1), Map.of());
    }

    private static ApsRequest.StepResource option(String id, int seconds) {
        return new ApsRequest.StepResource(ApsRequest.ResourceType.DEVICE, id, 0, false, seconds,
                BigDecimal.ONE, 0, 0, BigDecimal.ONE, true, false, Map.of());
    }

    private static ApsRequest.Resource resource(String id) {
        return new ApsRequest.Resource(ApsRequest.ResourceType.DEVICE, id, id, id, BigDecimal.ONE, List.of(), Map.of());
    }

    private static ApsResult.TaskResult variable(ApsResult.Solution solution) {
        return solution.taskList().stream().filter(t -> t.taskId().equals("VAR")).findFirst().orElseThrow();
    }

    private static ApsResult.ResourceAssignment assignment(String id, String label, String capacity) {
        return new ApsResult.ResourceAssignment(ApsRequest.ResourceType.DEVICE, id, label, label, new BigDecimal(capacity));
    }

    private static ApsResult.TimeSegment segment(int start, int end) {
        return new ApsResult.TimeSegment(START.plusHours(start), START.plusHours(end));
    }

    private static ApsResult.TaskResult keyTask(String id, String quantity, List<ApsResult.ResourceAssignment> assignments,
            List<ApsResult.TimeSegment> segments) {
        var step = new ApsResult.StepResult(id + "-SPLIT-001", "S", "S", "工序", START, START.plusHours(3),
                0, 7200, false, assignments, segments);
        return new ApsResult.TaskResult(id, id + "-SPLIT-001", 1, 1, new BigDecimal(quantity), id,
                START, START.plusDays(1), START, START.plusHours(3), true, 0, List.of(step));
    }
}
