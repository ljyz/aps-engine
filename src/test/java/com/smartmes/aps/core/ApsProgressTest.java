package com.smartmes.aps.core;

import com.smartmes.aps.api.ApsEngine;
import com.smartmes.aps.api.ApsEngines;
import com.smartmes.aps.api.ApsRequest;
import com.smartmes.aps.api.ApsResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ApsProgressTest {
    private final ApsEngine engine = ApsEngines.createDefault();
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 1, 8, 0);
    private record Event(String stage, int percent, String message, long thread) {}

    @Test void heuristicCountsActualCombinationsInsideEachCandidate() {
        var events = collect(request(false, 2, 256, 1, 4, 3, 0));
        var intermediate = events.stream().filter(e -> e.message.contains("候选 1/2")
                && e.message.contains("工序 1/2，已评估资源组合 3/6")).findFirst().orElseThrow();
        // 每个候选实际6+1个工作单位，共2个候选；上限256不作为实际分母。
        assertEquals(28, intermediate.percent);
        assertTrue(events.stream().anyMatch(e -> e.message.contains("候选 2/2")));
        assertMonotonic(events);
    }

    @Test void configuredLimitCapsActualCombinations() {
        var events = collect(request(false, 1, 4, 1, 4, 3, 0));
        assertTrue(events.stream().anyMatch(e -> e.message.contains("资源组合 4/4")));
        assertFalse(events.stream().anyMatch(e -> e.message.contains("/6")));
    }

    @Test void parallelCallbacksRemainOnSchedulingThreadAndKeepResults() {
        var input = request(false, 2, 256, 4, 4, 3, 0);
        long caller = Thread.currentThread().getId();
        var events = new ArrayList<Event>();
        ApsResult withProgress = engine.schedule(input, (id, stage, percent, message) ->
                events.add(new Event(stage, percent, message, Thread.currentThread().getId())), null);
        assertEquals(ApsResult.Status.SUCCESS, withProgress.status());
        assertTrue(events.stream().allMatch(e -> e.thread == caller));
        assertEquals(engine.schedule(input).bestSolution(), withProgress.bestSolution());
        assertMonotonic(events);
    }

    @Test void geneticProgressAdvancesInsideFirstGeneration() {
        var events = collect(request(true, 1, 256, 1, 4, 3, 0));
        assertTrue(events.stream().anyMatch(e -> e.stage.equals("EVOLVING")
                && e.message.contains("第 1/3 代，个体 2/4") && e.percent > 10 && e.percent < 39));
        assertTrue(events.stream().anyMatch(e -> e.message.contains("12/12 个个体")));
        assertMonotonic(events);
    }

    @Test void earlyConvergenceReportsActualBudgetAndFinalizingReason() {
        var events = collect(request(true, 1, 256, 1, 4, 10, 1));
        var convergence = events.stream().filter(e -> e.stage.equals("CONVERGED")).findFirst().orElseThrow();
        assertTrue(convergence.percent < 95);
        assertTrue(convergence.message.contains("2/10 代"));
        assertTrue(events.stream().anyMatch(e -> e.stage.equals("FINALIZING")
                && e.message.contains("已提前收敛") && e.message.contains("8/40 个个体")));
        assertEquals(100, events.get(events.size() - 1).percent);
    }

    @Test void cancellationBetweenCombinationsNeverReportsCompletion() {
        var cancel = new AtomicBoolean();
        var events = new ArrayList<Event>();
        ApsResult result = engine.schedule(request(false, 1, 256, 1, 4, 3, 0), (id, stage, percent, message) -> {
            events.add(new Event(stage, percent, message, Thread.currentThread().getId()));
            if (message.contains("已评估资源组合 1/6")) cancel.set(true);
        }, cancel::get);
        assertEquals(ApsResult.Status.CANCELLED, result.status());
        assertEquals("CANCELLED", events.get(events.size() - 1).stage);
        assertFalse(events.stream().anyMatch(e -> e.percent == 100));
        assertMonotonic(events);
    }

    @Test void timeoutReportsTerminalStateWithoutFakeCompletion() {
        var base = request(false, 1, 256, 1, 4, 3, 0);
        var input = withParam(base, param(false, Duration.ofNanos(1), 1, 256, 1, 4, 3, 0));
        var events = new ArrayList<Event>();
        var result = engine.schedule(input, (id, stage, percent, message) ->
                events.add(new Event(stage, percent, message, Thread.currentThread().getId())), null);
        assertEquals(ApsResult.Status.TIMEOUT, result.status());
        assertEquals("TIMEOUT", events.get(events.size() - 1).stage);
        assertFalse(events.stream().anyMatch(e -> e.percent == 100));
    }

    @Test void backwardFallbackReservesBudgetAndNeverRegresses() {
        var steps = List.of(step("A", 120, List.of()), step("B", 60, List.of()));
        var route = new ApsRequest.Route("R", "R", "R", steps, List.of(new ApsRequest.StepEdge("A", "B", 0, null)));
        var input = input(ApsRequest.SolverType.HEURISTIC, "BACKWARD", route, List.of(), START.plusMinutes(1),
                param(true, Duration.ofMinutes(1), 1, 256, 1, 4, 3, 0));
        var events = collect(input);
        assertTrue(events.stream().anyMatch(e -> e.message.contains("正在延期补排") && e.percent < 95));
        assertMonotonic(events);
    }

    @Test void optionalAndDisabledToolingUseActualCombinationCount() {
        var options = List.of(candidate(ApsRequest.ResourceType.DEVICE, "D", true),
                candidate(ApsRequest.ResourceType.TEAM, "T", false),
                candidate(ApsRequest.ResourceType.TOOLING, "TOOL", true));
        var route = new ApsRequest.Route("R", "R", "R", List.of(step("A", 60, options), step("B", 60, List.of())),
                List.of(new ApsRequest.StepEdge("A", "B", 0, null)));
        var input = input(ApsRequest.SolverType.HEURISTIC, "FORWARD", route,
                List.of(resource(ApsRequest.ResourceType.DEVICE,"D"),resource(ApsRequest.ResourceType.TEAM,"T"),
                        resource(ApsRequest.ResourceType.TOOLING,"TOOL")),START.plusDays(1),
                param(false,Duration.ofMinutes(1),1,256,1,4,3,0));
        var events=collect(input);
        assertTrue(events.stream().anyMatch(e -> e.message.contains("工序 1/2，已评估资源组合 1/2") && e.percent == 38));
    }

    @Test void nullResourceListRemainsSupported() {
        var route = new ApsRequest.Route("R","R","R",List.of(step("A",60,List.of())),List.of());
        var input=input(ApsRequest.SolverType.HEURISTIC,"FORWARD",route,null,START.plusDays(1),
                param(false,Duration.ofMinutes(1),1,256,1,4,3,0));
        assertEquals(ApsResult.Status.SUCCESS, engine.schedule(input).status());
    }

    @Test void progressThrottlesUnchangedPercentAndBuildsMessagesOnlyWhenPublished() {
        var clock = new AtomicLong();
        var messages = new AtomicLong();
        var events = new ArrayList<Event>();
        var progress = new ApsEngineProgress("R",(id,stage,percent,message) -> events.add(new Event(stage,percent,message,0)),clock::get);
        progress.report("SCHEDULING",10,() -> "message " + messages.incrementAndGet());
        clock.set(100_000_000);
        progress.report("SCHEDULING",10,() -> "message " + messages.incrementAndGet());
        assertEquals(1,messages.get());
        clock.set(500_000_000);
        progress.report("SCHEDULING",10,() -> "message " + messages.incrementAndGet());
        progress.report("SCHEDULING",11,"advanced");
        progress.report("FAILED",0,"failed");
        assertEquals(4,events.size());
        assertEquals(11,events.get(3).percent);
        assertEquals(2,messages.get());
    }

    private List<Event> collect(ApsRequest input) {
        var events = new ArrayList<Event>();
        var result = engine.schedule(input,(id,stage,percent,message) ->
                events.add(new Event(stage,percent,message,Thread.currentThread().getId())),null);
        assertEquals(ApsResult.Status.SUCCESS,result.status());
        return events;
    }

    private static void assertMonotonic(List<Event> events) {
        for(int i=0;i<events.size();i++) {
            assertTrue(events.get(i).percent >= 0 && events.get(i).percent <= 100);
            if(i>0) assertTrue(events.get(i).percent >= events.get(i-1).percent,events.toString());
        }
    }

    private static ApsRequest request(boolean genetic,int searches,int limit,int parallel,int population,int generations,int stagnation) {
        var options = new ArrayList<ApsRequest.StepResource>();
        var resources = new ArrayList<ApsRequest.Resource>();
        for(int i=1;i<=2;i++) {String id="D"+i;options.add(candidate(ApsRequest.ResourceType.DEVICE,id,true));resources.add(resource(ApsRequest.ResourceType.DEVICE,id));}
        for(int i=1;i<=3;i++) {String id="T"+i;options.add(candidate(ApsRequest.ResourceType.TEAM,id,true));resources.add(resource(ApsRequest.ResourceType.TEAM,id));}
        var route=new ApsRequest.Route("R","R","R",List.of(step("A",60,options),step("B",60,List.of())),List.of(new ApsRequest.StepEdge("A","B",0,null)));
        return input(genetic?ApsRequest.SolverType.GENETIC:ApsRequest.SolverType.HEURISTIC,"FORWARD",route,resources,START.plusDays(1),param(false,Duration.ofMinutes(1),searches,limit,parallel,population,generations,stagnation));
    }

    private static ApsRequest.AlgorithmParam param(boolean late,Duration timeout,int searches,int limit,int parallel,int population,int generations,int stagnation) {
        return new ApsRequest.AlgorithmParam(true,true,late,Duration.ofDays(1),false,0,0,1,1,1,parallel,timeout,BigDecimal.ONE,
                new ApsRequest.HeuristicParam(searches,limit,true),new ApsRequest.GeneticParam(population,generations,new BigDecimal("0.8"),new BigDecimal("0.1"),1,2,stagnation,new BigDecimal("0.3")));
    }
    private static ApsRequest input(ApsRequest.SolverType solver,String direction,ApsRequest.Route route,List<ApsRequest.Resource> resources,LocalDateTime end,ApsRequest.AlgorithmParam param) {
        var task=new ApsRequest.Task("TASK",null,"W",null,"R",route,"SKU","SKU",BigDecimal.ONE,BigDecimal.ONE,1,false,START,end,Map.of());
        return new ApsRequest("1.0","TEST",solver,direction,START,1L,param,List.of(),resources,List.of(task),ApsRequest.OutputParam.defaults());
    }
    private static ApsRequest withParam(ApsRequest input,ApsRequest.AlgorithmParam param) {
        return new ApsRequest(input.schemaVersion(),input.requestId(),input.solverType(),input.algorithmId(),input.currentTime(),input.randomSeed(),param,input.shiftScheduleList(),input.resourceList(),input.taskList(),input.outputParam());
    }
    private static ApsRequest.Step step(String id,int seconds,List<ApsRequest.StepResource> options) {
        return new ApsRequest.Step(id,id,id,id,false,options.stream().anyMatch(o -> o.resourceType()==ApsRequest.ResourceType.DEVICE),0,seconds,options,Map.of());
    }
    private static ApsRequest.StepResource candidate(ApsRequest.ResourceType type,String id,boolean required) {
        return new ApsRequest.StepResource(type,id,0,true,null,null,null,null,BigDecimal.ONE,required,false,Map.of());
    }
    private static ApsRequest.Resource resource(ApsRequest.ResourceType type,String id) {
        return new ApsRequest.Resource(type,id,id,id,BigDecimal.ONE,List.of(),Map.of());
    }
}
