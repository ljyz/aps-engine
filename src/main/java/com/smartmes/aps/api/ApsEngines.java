package com.smartmes.aps.api;

import com.smartmes.aps.core.DefaultApsEngine;

/** APS 引擎工厂，隔离调用方与具体实现类。 */
public final class ApsEngines {
    private ApsEngines() {}

    /**
     * 创建第一版确定性有限产能排产引擎。
     *
     * @return 新的排产引擎实例
     */
    public static ApsEngine createDefault() {
        return new DefaultApsEngine();
    }
}
