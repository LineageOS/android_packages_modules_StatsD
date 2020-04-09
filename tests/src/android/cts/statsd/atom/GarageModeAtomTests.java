/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.cts.statsd.atom;

import com.android.os.AtomsProto.Atom;
import com.android.os.StatsLog.EventMetricData;

import java.util.List;

/**
 * Verifies that Automotive's Garage Mode reports its status.
 * Statsd atom tests are done via adb (hostside).
 */
public class GarageModeAtomTests extends AtomTestCase {

    private static final String TAG = "Statsd.GarageModeAtomTests";
    private static final int SHORT_SLEEP = 100; // Milliseconds
    private static final int TRY_LIMIT = WAIT_TIME_SHORT / SHORT_SLEEP;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    public void testGarageModeOnOff() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_AUTOMOTIVE, true)) {
            return;
        }

        final int atomTag = Atom.GARAGE_MODE_INFO_FIELD_NUMBER;
        createAndUploadConfig(atomTag);

        // Flush any old metrics
        List<EventMetricData> data = getEventMetricDataList();

        turnOnGarageMode();
        waitForGarageModeState(true);

        turnOffGarageMode();
        waitForGarageModeState(false);
    }

    private void turnOnGarageMode() throws Exception {
        getDevice().executeShellCommand("cmd car_service garage-mode on");
    }
    private void turnOffGarageMode() throws Exception {
        getDevice().executeShellCommand("cmd car_service garage-mode off");
    }

    private void waitForGarageModeState(boolean requiredState) throws Exception {
        for (int tryCount = 0; tryCount < TRY_LIMIT; tryCount++) {
            List<EventMetricData> data = getEventMetricDataList();
            for (EventMetricData d : data) {
                boolean isGarageMode = d.getAtom().getGarageModeInfo().getIsGarageMode();
                if (isGarageMode == requiredState) {
                    return;
                }
            }
            Thread.sleep(SHORT_SLEEP);
        }
        assertTrue("Did not receive an atom with Garage Mode "
                   + (requiredState ? "ON" : "OFF"), false);
    }
}
