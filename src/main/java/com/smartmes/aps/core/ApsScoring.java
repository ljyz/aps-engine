package com.smartmes.aps.core;

import com.smartmes.aps.api.ApsRequest;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/** 请求固定的百分制目标函数，搜索期间不随候选或排班范围变化。 */
final class ApsScoring {
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private final LocalDateTime baseline;
    private final BigDecimal windowSeconds;
    private final long finishWeight;
    private final long achievementWeight;
    private final long onTimeWeight;

    ApsScoring(ApsRequest request) {
        LocalDateTime firstStart = request.taskList().stream().map(ApsRequest.Task::planStartTime)
                .min(LocalDateTime::compareTo).orElse(request.currentTime());
        baseline = firstStart.isAfter(request.currentTime()) ? firstStart : request.currentTime();
        LocalDateTime lastDue = request.taskList().stream().map(ApsRequest.Task::planEndTime)
                .max(LocalDateTime::compareTo).orElse(baseline);
        windowSeconds = BigDecimal.valueOf(Math.max(1, Duration.between(baseline, lastDue).getSeconds()));
        ApsRequest.AlgorithmParam p = request.algorithmParam() == null
                ? ApsRequest.AlgorithmParam.defaults() : request.algorithmParam();
        long finish = Math.max(0, p.finishTimeWeight());
        long achievement = Math.max(0, p.achievementRateWeight());
        long onTime = Math.max(0, p.onTimeDeliveryWeight());
        boolean allZero = finish + achievement + onTime == 0;
        finishWeight = allZero ? 1 : finish;
        achievementWeight = allZero ? 1 : achievement;
        onTimeWeight = allZero ? 1 : onTime;
    }

    /** 达成和准交均以全部待排拆分工单为分母；未排工单不能提高准交率。 */
    BigDecimal score(int total, int scheduled, int onTime, LocalDateTime end) {
        if (total <= 0 || scheduled <= 0) return BigDecimal.ZERO.setScale(12);
        int completed = Math.min(total, scheduled);
        int delivered = Math.max(0, Math.min(completed, onTime));
        BigDecimal achievement = BigDecimal.valueOf(completed).divide(BigDecimal.valueOf(total), PRECISION);
        BigDecimal delivery = BigDecimal.valueOf(delivered).divide(BigDecimal.valueOf(total), PRECISION);
        BigDecimal finish = BigDecimal.ZERO;
        if (end != null) {
            BigDecimal elapsed = BigDecimal.valueOf(Math.max(0, Duration.between(baseline, end).getSeconds()));
            // 平滑衰减避免超过交期后时间得分全部归零，也避免延后开始缩短跨度而虚增得分。
            finish = windowSeconds.divide(windowSeconds.add(elapsed), PRECISION);
        }
        return finish.multiply(BigDecimal.valueOf(finishWeight))
                .add(achievement.multiply(BigDecimal.valueOf(achievementWeight)))
                .add(delivery.multiply(BigDecimal.valueOf(onTimeWeight)))
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(finishWeight + achievementWeight + onTimeWeight), PRECISION)
                .max(BigDecimal.ZERO).min(HUNDRED).setScale(12, RoundingMode.HALF_UP);
    }
}
