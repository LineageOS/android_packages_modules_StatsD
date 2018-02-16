/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.cts.device.statsd;

import android.util.StatsLog;

import com.android.os.AtomsProto;

import org.junit.Test;

public class MetricsTests {
    @Test
    public void testSimpleEventCountMetric() {
        StatsLog.write(StatsLog.SCREEN_STATE_CHANGED,
                StatsLog.SCREEN_STATE_CHANGED__STATE__DISPLAY_STATE_OFF);
        StatsLog.write(StatsLog.SCREEN_STATE_CHANGED,
                StatsLog.SCREEN_STATE_CHANGED__STATE__DISPLAY_STATE_ON);
    }

    @Test
    public void testEventCountWithCondition() {
        StatsLog.write(StatsLog.PLUGGED_STATE_CHANGED, 1);
        StatsLog.write(StatsLog.SCREEN_STATE_CHANGED,
                StatsLog.SCREEN_STATE_CHANGED__STATE__DISPLAY_STATE_OFF);
        StatsLog.write(StatsLog.PLUGGED_STATE_CHANGED, 2);
    }
}
