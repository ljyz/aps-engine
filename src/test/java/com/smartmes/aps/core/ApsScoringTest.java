package com.smartmes.aps.core;

import com.smartmes.aps.api.ApsRequest;
import com.smartmes.aps.api.ApsResult;
import com.smartmes.aps.api.ApsEngines;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApsScoringTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 27, 8, 0);

    @Test
    void equalWeightsGiveEachNormalizedCriterionOneThird() {
        // 时间=50，达成=100，准交=50，总分=66.666...
        assertScore("66.666666666667", scoring(1, 1, 1).score(2, 2, 1, START.plusHours(10)));
    }

    @Test
    void completionAndDeliveryUseAllRequestedSplitTasks() {
        assertScore("50", scoring(0, 1, 0).score(4, 2, 2, START.plusHours(5)));
        assertScore("50", scoring(0, 0, 1).score(4, 2, 2, START.plusHours(5)));
        assertScore("25", scoring(0, 0, 1).score(4, 2, 1, START.plusHours(5)));
    }

    @Test
    void changingRelativeWeightsChangesTradeoffAndScalingDoesNot() {
        assertScore("83.333333333333", scoring(0, 2, 1).score(2, 2, 1, START.plusHours(10)));
        assertScore("66.666666666667", scoring(0, 1, 2).score(2, 2, 1, START.plusHours(10)));
        assertEquals(scoring(1, 2, 3).score(4, 3, 2, START.plusHours(7)),
                scoring(10, 20, 30).score(4, 3, 2, START.plusHours(7)));
    }

    @Test
    void earlierFinalCompletionWinsEvenWhenBothAreOnTime() {
        ApsScoring score = scoring(1, 1, 1);
        assertTrue(score.score(2, 2, 2, START.plusHours(4))
                .compareTo(score.score(2, 2, 2, START.plusHours(5))) > 0);
    }

    @Test
    void finishScoreContinuesToDistinguishLatePlans() {
        ApsScoring score = scoring(1, 1, 1);
        assertTrue(score.score(2, 2, 0, START.plusHours(20))
                .compareTo(score.score(2, 2, 0, START.plusHours(30))) > 0);
    }

    @Test
    void zeroAndNegativeWeightsAreSafeAndAllZeroFallsBackToEqualWeights() {
        assertEquals(scoring(1, 1, 1).score(2, 2, 1, START.plusHours(4)),
                scoring(0, 0, 0).score(2, 2, 1, START.plusHours(4)));
        assertEquals(scoring(0, 1, 0).score(2, 2, 1, START.plusHours(4)),
                scoring(-1, 1, -1).score(2, 2, 1, START.plusHours(4)));
        assertScore("0", scoring(1, 1, 1).score(2, 0, 0, null));
        assertScore("0", scoring(1, 1, 1).score(0, 0, 0, null));
        assertScore("100", scoring(1, 1, 1).score(2, 2, 2, START));
    }

    @Test
    void weightSumDoesNotOverflow() {
        assertEquals(scoring(1, 1, 1).score(2, 2, 1, START.plusHours(10)),
                scoring(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)
                        .score(2, 2, 1, START.plusHours(10)));
    }

    @Test
    void waitingForFutureReleaseIsExcludedFromCommonTimeBaseline() {
        ApsRequest request = request(param(1, 0, 0), List.of(task(START.plusHours(2), START.plusHours(12), List.of())),
                List.of(), List.of(), ApsRequest.SolverType.HEURISTIC);
        assertScore("50", new ApsScoring(request).score(1, 1, 1, START.plusHours(12)));
        ApsRequest expired = request(param(1, 0, 0), List.of(task(START.minusHours(2), START.minusHours(1), List.of())),
                List.of(), List.of(), ApsRequest.SolverType.HEURISTIC);
        assertScore("50", new ApsScoring(expired).score(1, 1, 0, START.plusSeconds(1)));
    }

    @Test
    void calendarExtensionDoesNotRescaleExistingCandidateScores() {
        ApsRequest base = request(param(1, 1, 1), List.of(task(START, START.plusHours(10), List.of())),
                List.of(), List.of(), ApsRequest.SolverType.HEURISTIC);
        ApsRequest extended = request(param(1, 1, 1), base.taskList(), List.of(),
                List.of(day(START.plusDays(60), List.of())), ApsRequest.SolverType.HEURISTIC);
        assertEquals(new ApsScoring(base).score(1, 1, 1, START.plusHours(3)),
                new ApsScoring(extended).score(1, 1, 1, START.plusHours(3)));
    }

    @Test
    void preservesPrecisionUntilBackendDisplayRounding() {
        ApsScoring score = new ApsScoring(request(param(1, 1, 1),
                List.of(task(START, START.plusDays(30), List.of())), List.of(), List.of(), ApsRequest.SolverType.HEURISTIC));
        BigDecimal earlier = score.score(1, 1, 1, START.plusSeconds(100));
        BigDecimal later = score.score(1, 1, 1, START.plusSeconds(101));
        assertTrue(earlier.compareTo(later) > 0);
        assertEquals(earlier.setScale(2, java.math.RoundingMode.HALF_UP), later.setScale(2, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void geneticSearchChoosesFasterResourceWhenAvailabilityIsEqual() {
        ApsResult result = search(false);
        assertEquals(ApsResult.Status.SUCCESS, result.status());
        assertEquals("FAST", result.bestSolution().itemList().get(0).deviceId());
        assertEquals(START.plusHours(1), result.bestSolution().planEndTime());
        assertTrue(result.bestSolution().score().compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(result.bestSolution().score().compareTo(BigDecimal.valueOf(100)) <= 0);
        for (int i = 1; i < result.candidateSolutionList().size(); i++) {
            assertTrue(result.candidateSolutionList().get(i - 1).score()
                    .compareTo(result.candidateSolutionList().get(i).score()) >= 0);
        }
    }

    @Test
    void geneticSearchPrefersEarlierCompletionOverFasterDeviceAvailableTomorrow() {
        ApsResult result = search(true);
        assertEquals("SLOW", result.bestSolution().itemList().get(0).deviceId());
        assertEquals(START.plusHours(2), result.bestSolution().planEndTime());
    }

    @Test
    void deliveryWeightGuidesGeneticTaskOrder() {
        var first = task(START, START.plusHours(4), List.of(option("D", 3600)));
        var second = task(START, START.plusHours(2), List.of(option("D", 7200)));
        var named = new ApsRequest.Task("T2", null, "T2", null, second.routeId(), second.route(),
                "SKU", "SKU", BigDecimal.ONE, BigDecimal.ONE, 1, false,
                second.planStartTime(), second.planEndTime(), Map.of());
        var result = ApsEngines.createDefault().schedule(request(param(0, 0, 1), List.of(first, named),
                List.of(resource("D")), List.of(day(START, List.of("D"))), ApsRequest.SolverType.GENETIC));
        assertEquals(2, result.statistics().onTimeTaskCount());
        assertScore("100", result.bestSolution().score());
    }

    @Test
    void incompleteIndividualsNeverBecomeSavableCandidates() {
        var first = task(START, START.plusHours(2), List.of(option("D1", 7200), option("D2", 3600)));
        var second = task(START, START.plusHours(2), List.of(option("D1", 3600)));
        var named = new ApsRequest.Task("T2", null, "T2", null, second.routeId(), second.route(),
                "SKU", "SKU", BigDecimal.ONE, BigDecimal.ONE, 1, false,
                second.planStartTime(), second.planEndTime(), Map.of());
        var shift = new ApsRequest.ShiftSchedule(START.toLocalDate(), List.of(new ApsRequest.Shift("SHORT", "SHORT",
                "短班", LocalTime.of(8, 0), LocalTime.of(10, 0), false, 120, List.of(), List.of("D1", "D2"))));
        var result = ApsEngines.createDefault().schedule(request(param(1, 1, 1), List.of(first, named),
                List.of(resource("D1"), resource("D2")), List.of(shift), ApsRequest.SolverType.GENETIC));
        assertEquals(ApsResult.Status.SUCCESS, result.status());
        assertEquals(2, result.bestSolution().taskList().size());
        assertTrue(result.candidateSolutionList().stream().allMatch(c -> c.feasible() && c.taskList().size() == 2));
    }

    private static ApsResult search(boolean delayedFast) {
        var candidates = List.of(option("SLOW", 7200), option("FAST", 3600));
        var resources = List.of(resource("SLOW"), resource("FAST"));
        var shifts = List.of(day(START, delayedFast ? List.of("SLOW") : List.of("SLOW", "FAST")),
                day(START.plusDays(1), List.of("SLOW", "FAST")));
        return ApsEngines.createDefault().schedule(request(param(1, 1, 1),
                List.of(task(START, START.plusDays(2), candidates)), resources, shifts, ApsRequest.SolverType.GENETIC));
    }

    private static ApsRequest.StepResource option(String id, int seconds) {
        return new ApsRequest.StepResource(ApsRequest.ResourceType.DEVICE, id, 0, false,
                seconds, BigDecimal.ONE, 0, 0, BigDecimal.ONE, true, false, Map.of());
    }

    private static ApsRequest.Resource resource(String id) {
        return new ApsRequest.Resource(ApsRequest.ResourceType.DEVICE, id, id, id, BigDecimal.ONE, List.of(), Map.of());
    }

    private static ApsRequest.ShiftSchedule day(LocalDateTime date, List<String> devices) {
        return new ApsRequest.ShiftSchedule(date.toLocalDate(), List.of(new ApsRequest.Shift("DAY", "DAY", "白班",
                LocalTime.of(8, 0), LocalTime.of(17, 0), false, 540, List.of(), devices)));
    }

    private static ApsScoring scoring(int f, int a, int d) {
        return new ApsScoring(request(param(f, a, d), List.of(task(START, START.plusHours(10), List.of())),
                List.of(), List.of(), ApsRequest.SolverType.HEURISTIC));
    }

    private static ApsRequest.Task task(LocalDateTime start, LocalDateTime end, List<ApsRequest.StepResource> resources) {
        var step = new ApsRequest.Step("S", "S", "S", "加工", true, !resources.isEmpty(), 0, 3600, resources, Map.of());
        var route = new ApsRequest.Route("R", "R", "路线", List.of(step), List.of());
        return new ApsRequest.Task("T", null, "T", null, "R", route, "SKU", "SKU", BigDecimal.ONE, BigDecimal.ONE,
                1, false, start, end, Map.of());
    }

    private static ApsRequest request(ApsRequest.AlgorithmParam param, List<ApsRequest.Task> tasks,
            List<ApsRequest.Resource> resources, List<ApsRequest.ShiftSchedule> shifts, ApsRequest.SolverType solver) {
        return new ApsRequest("1.0", "SCORE", solver, "FORWARD", START, 1L, param, shifts, resources, tasks,
                new ApsRequest.OutputParam(5, true, true, true, true));
    }

    private static ApsRequest.AlgorithmParam param(int f, int a, int d) {
        var defaults = ApsRequest.AlgorithmParam.defaults();
        return new ApsRequest.AlgorithmParam(true, true, false, Duration.ofDays(30), false, 0, 0,
                f, a, d, 1, Duration.ofMinutes(1), BigDecimal.ONE, new ApsRequest.HeuristicParam(3, 256, true),
                new ApsRequest.GeneticParam(30, 20, new BigDecimal("0.8"), new BigDecimal("0.1"), 2, 3, 0,
                        new BigDecimal("0.3")));
    }

    private static void assertScore(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
