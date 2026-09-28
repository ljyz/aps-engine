package com.smartmes.aps.core;
import com.smartmes.aps.api.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferModeTest {
    private final ApsEngine engine = ApsEngines.createDefault();
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 1, 8, 0);
    @Test void batchIsDefault() {
        var s = run("FORWARD", null, 60, 60).bestSolution().taskList().get(0).stepList();
        assertEquals(START.plusMinutes(10), s.get(1).planStartTime());
        assertEquals(START.plusMinutes(20), s.get(1).planEndTime());
    }
    @Test void pieceReleasesFirstUnitWithoutSplittingOrders() {
        var result = run("FORWARD", "PIECE", 60, 60);
        var task = result.bestSolution().taskList().get(0);
        assertEquals(new BigDecimal("10"), task.planNum());
        assertEquals(1, result.bestSolution().taskList().size());
        assertEquals(2, result.bestSolution().itemList().size());
        assertEquals(START.plusMinutes(1), task.stepList().get(1).planStartTime());
        assertEquals(START.plusMinutes(11), task.stepList().get(1).planEndTime());
    }
    @Test void doesNotCheckSubsequentSupplyQuantity() {
        var s = run("FORWARD", "PIECE", 120, 30).bestSolution().taskList().get(0).stepList();
        assertEquals(START.plusMinutes(2), s.get(1).planStartTime());
        assertEquals(START.plusMinutes(7), s.get(1).planEndTime());
        assertTrue(s.get(1).planEndTime().isBefore(s.get(0).planEndTime()));
    }
    @Test void firstUnitCrossesRestPeriodUsingWorkingSegments() {
        var shifts = List.of(new ApsRequest.ShiftSchedule(START.toLocalDate(), List.of(
            new ApsRequest.Shift("1", "1", "1", LocalTime.of(8,0), LocalTime.of(8,0,4), false, 0, List.of()),
            new ApsRequest.Shift("2", "2", "2", LocalTime.of(9,0), LocalTime.of(10,0), false, 60, List.of()))));
        var input = request("FORWARD", "2", List.of(step("A",6,"PIECE"),step("B",3,"PIECE")),
            List.of(edge("A","B",0,null)),shifts,List.of());
        var s = engine.schedule(input).bestSolution().taskList().get(0).stepList();
        assertEquals(START.plusHours(1).plusSeconds(2),s.get(1).planStartTime());
        assertEquals(2,s.get(0).workSegmentList().size());
    }
    @Test void minimumIntervalUsesFirstRelease() {
        var input = request("FORWARD","10",List.of(step("A",60,"PIECE"),step("B",60,"PIECE")),
            List.of(edge("A","B",2,null)),List.of(),List.of());
        assertEquals(START.plusMinutes(3),engine.schedule(input).bestSolution().taskList().get(0).stepList().get(1).planStartTime());
    }
    @Test void maximumIntervalUsesFirstRelease() {
        var device = device("D",List.of(new ApsRequest.ResourceOccupancy("O",START,START.plusMinutes(5),BigDecimal.ONE,"EXISTING_PLAN","OLD",null)));
        var input = request("FORWARD","10",List.of(step("A",60,"PIECE"),step("B",60,"PIECE",candidate("D",true))),
            List.of(edge("A","B",0,1)),List.of(),List.of(device));
        assertEquals(ApsResult.Status.INFEASIBLE,engine.schedule(input).status());
    }
    @Test void backwardPieceUsesFirstUnitDeadline() {
        var s = run("BACKWARD","PIECE",60,60).bestSolution().taskList().get(0).stepList();
        assertEquals(START.plusMinutes(50),s.get(1).planStartTime());
        assertEquals(START.plusMinutes(49),s.get(0).planStartTime());
        assertEquals(START.plusMinutes(59),s.get(0).planEndTime());
    }
    @Test void backwardBatchWaitsForEntirePredecessor() {
        var s = run("BACKWARD","BATCH",60,60).bestSolution().taskList().get(0).stepList();
        assertEquals(START.plusMinutes(40),s.get(0).planStartTime());
        assertEquals(s.get(0).planEndTime(),s.get(1).planStartTime());
    }
    @Test void sharedContinuousResourcePreventsOverlapInBothDirections() {
        for(String direction:List.of("FORWARD","BACKWARD")) {
            var c=candidate("D",true);
            var input=request(direction,"10",List.of(step("A",60,"PIECE",c),step("B",60,"PIECE",c)),
                List.of(edge("A","B",0,null)),List.of(),List.of(device("D",List.of())));
            var s=engine.schedule(input).bestSolution().taskList().get(0).stepList();
            assertFalse(s.get(0).planEndTime().isAfter(s.get(1).planStartTime()));
        }
    }
    @Test void allPredecessorsMustRelease() {
        var input=request("FORWARD","10",List.of(step("A",60,"PIECE"),step("B",120,"PIECE"),step("C",30,"PIECE")),
            List.of(edge("A","C",0,null),edge("B","C",0,null)),List.of(),List.of());
        assertEquals(START.plusMinutes(2),engine.schedule(input).bestSolution().taskList().get(0).stepList().get(2).planStartTime());
    }
    @Test void singleUnitAndFractionalRemainderReleaseAtBatchEnd() {
        for(String qty:List.of("1","0.5")) {
            var input=request("FORWARD",qty,List.of(step("A",60,"PIECE"),step("B",60,"PIECE")),List.of(edge("A","B",0,null)),List.of(),List.of());
            var s=engine.schedule(input).bestSolution().taskList().get(0).stepList();
            assertEquals(s.get(0).planEndTime(),s.get(1).planStartTime());
        }
    }
    @Test void invalidModeIsRejected() {assertEquals(ApsResult.Status.INVALID_INPUT,run("FORWARD","UNKNOWN",60,60).status());}
    @Test void engineAdvertisesCapability() {
        assertTrue(engine.getEngineInfo().algorithmList().stream().allMatch(a->a.supportedConstraintList().contains("PIECE_TRANSFER")));
    }
    @Test void backwardContinuousResourceCanOverlapDifferentDevice() {
        var input=request("BACKWARD","10",List.of(step("A",60,"PIECE",candidate("D1",true)),step("B",60,"PIECE",candidate("D2",true))),
            List.of(edge("A","B",2,2)),List.of(),List.of(device("D1",List.of()),device("D2",List.of())));
        var s=engine.schedule(input).bestSolution().taskList().get(0).stepList();
        assertEquals(START.plusMinutes(47),s.get(0).planStartTime());
        assertEquals(START.plusMinutes(57),s.get(0).planEndTime());
        assertEquals(START.plusMinutes(50),s.get(1).planStartTime());
    }
    @Test void backwardFirstPieceRespectsInterruptedAvailabilityAndMaximumGap() {
        var occupied=new ApsRequest.ResourceOccupancy("O",START.plusMinutes(49),START.plusMinutes(51),BigDecimal.ONE,"EXISTING_PLAN","OLD",null);
        var steps=List.of(step("A",60,"PIECE",candidate("D",false)),step("B",60,"PIECE"));
        var input=request("BACKWARD","10",steps,List.of(edge("A","B",0,1)),List.of(),List.of(device("D",List.of(occupied))));
        var s=engine.schedule(input).bestSolution().taskList().get(0).stepList();
        assertEquals(START.plusMinutes(48),s.get(0).planStartTime());
        assertEquals(START.plusMinutes(49),s.get(0).workSegmentList().get(0).endTime());
        assertEquals(START.plusMinutes(51),s.get(0).workSegmentList().get(1).startTime());
        assertEquals(START.plusHours(1),s.get(0).planEndTime());
        var tooSmall=request("BACKWARD","10",steps,List.of(edge("A","B",0,1)),List.of(),List.of(device("D",List.of(
            new ApsRequest.ResourceOccupancy("O",START.plusMinutes(48),START.plusMinutes(51),BigDecimal.ONE,"EXISTING_PLAN","OLD",null)))));
        assertEquals(ApsResult.Status.INFEASIBLE,engine.schedule(tooSmall).status());
    }
    @Test void geneticDecoderUsesPieceTransferInBothDirections() {
        for(String direction:List.of("FORWARD","BACKWARD")) {
            var input=request(direction,"10",List.of(step("A",60,"PIECE"),step("B",60,"PIECE")),List.of(edge("A","B",0,null)),List.of(),List.of());
            var p=input.algorithmParam();
            var param=new ApsRequest.AlgorithmParam(p.useIdleTime(),p.appendMode(),p.allowLateCompletion(),p.lateCompletionLimit(),p.enableToolingConstraint(),
                p.minProcessIntervalMinutes(),p.maxProcessIntervalHours(),p.finishTimeWeight(),p.achievementRateWeight(),p.onTimeDeliveryWeight(),
                1,p.timeout(),p.globalTimeScale(),p.heuristicParam(),new ApsRequest.GeneticParam(4,2,new BigDecimal("0.8"),new BigDecimal("0.1"),1,2,0,new BigDecimal("0.3")));
            var genetic=new ApsRequest(input.schemaVersion(),input.requestId(),ApsRequest.SolverType.GENETIC,direction,input.currentTime(),input.randomSeed(),param,
                input.shiftScheduleList(),input.resourceList(),input.taskList(),input.outputParam());
            var s=engine.schedule(genetic).bestSolution().taskList().get(0).stepList();
            assertEquals(s.get(0).planStartTime().plusMinutes(1),s.get(1).planStartTime());
        }
    }
    @Test void maximumIntervalIsCheckedToTheSecondInBothDirections() {
        var forward=request("FORWARD","10",List.of(step("A",60,"PIECE"),step("B",60,"PIECE",candidate("D",true))),
            List.of(edge("A","B",0,1)),List.of(),List.of(device("D",List.of(
                new ApsRequest.ResourceOccupancy("O",START,START.plusMinutes(2).plusSeconds(1),BigDecimal.ONE,"EXISTING_PLAN","OLD",null)))));
        assertEquals(ApsResult.Status.INFEASIBLE,engine.schedule(forward).status());
        var backward=request("BACKWARD","10",List.of(step("A",60,"PIECE",candidate("D",false)),step("B",60,"PIECE")),
            List.of(edge("A","B",0,1)),List.of(),List.of(device("D",List.of(
                new ApsRequest.ResourceOccupancy("O",START.plusMinutes(49).minusSeconds(1),START.plusMinutes(51),BigDecimal.ONE,"EXISTING_PLAN","OLD",null)))));
        assertEquals(ApsResult.Status.INFEASIBLE,engine.schedule(backward).status());
    }
    private ApsResult run(String direction,String mode,int a,int b) {
        return engine.schedule(request(direction,"10",List.of(step("A",a,mode),step("B",b,mode)),List.of(edge("A","B",0,null)),List.of(),List.of()));
    }
    private static ApsRequest request(String direction,String qty,List<ApsRequest.Step> steps,List<ApsRequest.StepEdge> edges,
            List<ApsRequest.ShiftSchedule> shifts,List<ApsRequest.Resource> resources) {
        var route=new ApsRequest.Route("R","R","路线",steps,edges);
        var task=new ApsRequest.Task("T",null,"W",null,"R",route,"SKU","SKU",new BigDecimal(qty),BigDecimal.TEN,1,false,START,START.plusHours(1),Map.of());
        return new ApsRequest("1.0","TEST",ApsRequest.SolverType.HEURISTIC,direction,START,1L,ApsRequest.AlgorithmParam.defaults(),shifts,resources,List.of(task),ApsRequest.OutputParam.defaults());
    }
    private static ApsRequest.Step step(String id,int seconds,String mode,ApsRequest.StepResource... candidates) {
        return new ApsRequest.Step(id,id,id,id,false,candidates.length>0,0,seconds,List.of(candidates),mode==null?Map.of():Map.of("transferMode",mode));
    }
    private static ApsRequest.StepEdge edge(String a,String b,Integer min,Integer max) {return new ApsRequest.StepEdge(a,b,min,max);}
    private static ApsRequest.StepResource candidate(String id,boolean continuous) {
        return new ApsRequest.StepResource(ApsRequest.ResourceType.DEVICE,id,0,true,null,null,null,null,BigDecimal.ONE,true,continuous,Map.of());
    }
    private static ApsRequest.Resource device(String id,List<ApsRequest.ResourceOccupancy> occupancies) {
        return new ApsRequest.Resource(ApsRequest.ResourceType.DEVICE,id,id,id,BigDecimal.ONE,occupancies,Map.of());
    }
}
