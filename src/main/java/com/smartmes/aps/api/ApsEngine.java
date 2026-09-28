package com.smartmes.aps.api;

import java.util.List;

/**
 * APS 自动排产引擎的公共入口。
 * <p>
 * 实现类只负责输入校验和排产计算，不查询数据库，也不保存排产结果。
 * 同一个引擎实例可以连续处理多个请求；每次排产使用独立的内部上下文。
 */
public interface ApsEngine {
    /**
     * 使用默认的进度监听器和取消信号执行排产。
     *
     * @param request 单次排产所需的完整数据快照
     * @return 排产结果；输入错误或无法排产时也通过状态和问题列表返回，不抛业务异常
     */
    ApsResult schedule(ApsRequest request);

    /**
     * 执行支持进度通知和主动取消的排产。
     *
     * @param request 单次排产所需的完整数据快照
     * @param listener 进度监听器，允许为 {@code null}
     * @param cancellationToken 取消信号，允许为 {@code null}
     * @return 排产结果
     */
    ApsResult schedule(ApsRequest request, ProgressListener listener, CancellationToken cancellationToken);

    /**
     * 只校验输入，不占用资源、不执行正式排产。
     *
     * @param request 待校验请求
     * @return 错误和警告列表；存在 {@code ERROR} 时不得执行排产
     */
    List<ApsResult.Issue> validate(ApsRequest request);

    /** @return 当前 JAR 的版本、协议版本和全部算法能力 */
    EngineInfo getEngineInfo();

    /** @return 当前 JAR 支持的算法清单 */
    default List<EngineInfo.AlgorithmDescriptor> getSupportedAlgorithms() {
        return getEngineInfo().algorithmList();
    }

    /** 排产进度回调。回调由执行排产的线程同步触发，调用方不应执行耗时操作。 */
    @FunctionalInterface
    interface ProgressListener {
        /**
         * @param requestId 排产请求 ID
         * @param stage 当前阶段，如 {@code VALIDATING}、{@code SCHEDULING}、{@code EVOLVING}、{@code CONVERGED}、{@code FINALIZING}、{@code COMPLETED}；异常终止为 {@code TIMEOUT}、{@code CANCELLED}、{@code FAILED}
         * @param percent 估算进度，范围为 0～100
         * @param message 中文进度说明
         */
        void onProgress(String requestId, String stage, int percent, String message);
    }

    /** 调用方提供的取消信号；算法会在任务、工序和串行资源组合边界检查该信号，并在并行等待期间定期检查。 */
    @FunctionalInterface
    interface CancellationToken {
        /** @return {@code true} 表示请求尽快停止本次排产 */
        boolean isCancellationRequested();
    }
}
