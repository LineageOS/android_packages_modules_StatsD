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
package android.cts.statsd.validation;

import static org.junit.Assert.assertTrue;

import android.cts.statsd.atom.AtomTestCase;
import android.os.BatteryStatsProto;

import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.os.StatsLog.CountMetricData;
import com.android.tradefed.log.LogUtil;
import com.android.tradefed.log.LogUtil.CLog;

import java.util.List;

/**
 * Side-by-side comparison between statsd and batterystats.
 */
public class BatteryStatsValidationTests extends AtomTestCase {

    private static final String TAG = "Statsd.BatteryStatsValidationTests";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        resetBatteryStatus();
        unplugDevice();
    }

    @Override
    protected void tearDown() throws Exception {
        plugInUsb();
    }

    public void testConnectivityStateChange() throws Exception {
        if (!hasFeature(FEATURE_WIFI, true)) return;
        if (!hasFeature(FEATURE_WATCH, false)) return;
        final String fileName = "BATTERYSTATS_CONNECTIVITY_STATE_CHANGE_COUNT.pbtxt";
        StatsdConfig config = new ValidationTestUtil().getConfig(fileName);
        LogUtil.CLog.d("Updating the following config:\n" + config.toString());
        uploadConfig(config);

        Thread.sleep(WAIT_TIME_SHORT);

        turnOnAirplaneMode();
        turnOffAirplaneMode();
        // wait for long enough for device to restore connection
        Thread.sleep(10_000);

        BatteryStatsProto batterystatsProto = getBatteryStatsProto();
        List<CountMetricData> countMetricData = getCountMetricDataList();
        assertEquals(1, countMetricData.size());
        assertEquals(1, countMetricData.get(0).getBucketInfoCount());
        assertTrue(countMetricData.get(0).getBucketInfo(0).getCount() > 0);
        assertEquals(batterystatsProto.getSystem().getMisc().getNumConnectivityChanges(),
                countMetricData.get(0).getBucketInfo(0).getCount());
    }
}
