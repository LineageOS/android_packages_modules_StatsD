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

import android.cts.statsd.atom.DeviceAtomTestCase;
import android.os.BatteryStatsProto;
import android.os.UidProto;
import android.os.UidProto.Wakelock;
import android.os.WakeLockLevelEnum;

import com.android.os.AtomsProto.Atom;
import com.android.os.AtomsProto.WakelockStateChanged;
import com.android.os.StatsLog.EventMetricData;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Side-by-side comparison between statsd and batterystats.
 */
public class ValidationTests extends DeviceAtomTestCase {

    private static final String TAG = "Statsd.ValidationTests";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    public void testPartialWakelock() throws Exception {
        turnScreenOff();
        resetBatteryStats();
        unplugDevice();

        final int atomTag = Atom.WAKELOCK_STATE_CHANGED_FIELD_NUMBER;
        Set<Integer> wakelockOn = new HashSet<>(Arrays.asList(
                WakelockStateChanged.State.ACQUIRE_VALUE,
                WakelockStateChanged.State.CHANGE_ACQUIRE_VALUE));
        Set<Integer> wakelockOff = new HashSet<>(Arrays.asList(
                WakelockStateChanged.State.RELEASE_VALUE,
                WakelockStateChanged.State.CHANGE_RELEASE_VALUE));

        final String EXPECTED_TAG = "StatsdPartialWakelock";
        final WakeLockLevelEnum EXPECTED_LEVEL = WakeLockLevelEnum.PARTIAL_WAKE_LOCK;

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(wakelockOn, wakelockOff);

        createAndUploadConfig(atomTag, true);  // True: uses attribution.
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testWakelockState");

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        BatteryStatsProto batterystatsProto = getBatteryStatsProto();

        resetBatteryStatus();

        //=================== verify that statsd is correct ===============//
        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
                atom -> atom.getWakelockStateChanged().getState().getNumber());

        for (EventMetricData event : data) {
            String tag = event.getAtom().getWakelockStateChanged().getTag();
            WakeLockLevelEnum type = event.getAtom().getWakelockStateChanged().getLevel();
            assertTrue("Expected tag: " + EXPECTED_TAG + ", but got tag: " + tag,
                    tag.equals(EXPECTED_TAG));
            assertTrue("Expected wakelock level: " + EXPECTED_LEVEL + ", but got level: " + type,
                    type == EXPECTED_LEVEL);
        }

        //=================== verify that batterystats is correct ===============//
        int uid = getUid();
        boolean foundUid = false;
        assertTrue(batterystatsProto.getUidsList().size() > 0);
        for (UidProto uidProto : batterystatsProto.getUidsList()) {
            if (uidProto.getUid() == uid) {
                foundUid = true;
                assertTrue(uidProto.getWakelocksList().size() > 0);
                boolean foundWakelock = false;
                for (Wakelock wl : uidProto.getWakelocksList()) {
                    if (wl.getName().equals(EXPECTED_TAG)) {
                        foundWakelock = true;
                        assertTrue(wl.hasPartial());
                        assertTrue(wl.getPartial().getDurationMs() > 0);
                        assertTrue(wl.getPartial().getCount() == 1);
                        assertTrue(wl.getPartial().getMaxDurationMs() >= 500);
                        assertTrue(wl.getPartial().getMaxDurationMs() < 700);
                        assertTrue(wl.getPartial().getTotalDurationMs() >= 500);
                        assertTrue(wl.getPartial().getTotalDurationMs() < 700);
                    }
                }
                assertTrue(foundWakelock);
            }
        }
        assertTrue(foundUid);
    }
}
