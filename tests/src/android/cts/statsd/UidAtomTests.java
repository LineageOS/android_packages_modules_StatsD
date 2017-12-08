/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.cts.statsd;

import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.os.AtomsProto.Atom;
import com.android.os.AtomsProto.BleScanResultReceived;
import com.android.os.AtomsProto.BleScanStateChanged;
import com.android.os.AtomsProto.BleUnoptimizedScanStateChanged;
import com.android.os.AtomsProto.GpsScanStateChanged;
import com.android.os.AtomsProto.WifiScanStateChanged;
import com.android.os.StatsLog.EventMetricData;

import java.util.List;

/**
 * Statsd atom tests that are done via app, for atoms that report a uid.
 */
public class UidAtomTests extends DeviceAtomTestCase {

    private static final String TAG = "Statsd.UidAtomTests";

    private static final boolean TESTS_ENABLED = false;

    // These constants are those in PackageManager.
    private static final String FEATURE_BLUETOOTH_LE = "android.hardware.bluetooth_le";
    private static final String FEATURE_LOCATION_GPS = "android.hardware.location.gps";
    private static final String FEATURE_WIFI = "android.hardware.wifi";

    public void testBleScan() throws Exception {
        if (!TESTS_ENABLED) return;
        if (!hasFeature(FEATURE_BLUETOOTH_LE, true)) return;

        final int atom = Atom.BLE_SCAN_STATE_CHANGED_FIELD_NUMBER;
        final int key = BleScanStateChanged.STATE_FIELD_NUMBER;
        final int stateOn = BleScanStateChanged.State.ON_VALUE;
        final int stateOff = BleScanStateChanged.State.OFF_VALUE;
        final int minTimeDiffMs = 1_500;
        final int maxTimeDiffMs = 3_000;

        List<EventMetricData> data = doDeviceMethodOnOff("testBleScanUnoptimized", atom, key,
                stateOn, stateOff, minTimeDiffMs, maxTimeDiffMs, true);

        BleScanStateChanged a0 = data.get(0).getAtom().getBleScanStateChanged();
        BleScanStateChanged a1 = data.get(1).getAtom().getBleScanStateChanged();
        assertTrue(a0.getState().getNumber() == stateOn);
        assertTrue(a1.getState().getNumber() == stateOff);
    }

    public void testBleUnoptimizedScan() throws Exception {
        if (!TESTS_ENABLED) return;
        if (!hasFeature(FEATURE_BLUETOOTH_LE, true)) return;

        final int atom = Atom.BLE_UNOPTIMIZED_SCAN_STATE_CHANGED_FIELD_NUMBER;
        final int key = BleUnoptimizedScanStateChanged.STATE_FIELD_NUMBER;
        final int stateOn = BleUnoptimizedScanStateChanged.State.ON_VALUE;
        final int stateOff = BleUnoptimizedScanStateChanged.State.OFF_VALUE;
        final int minTimeDiffMs = 1_500;
        final int maxTimeDiffMs = 3_000;

        List<EventMetricData> data = doDeviceMethodOnOff("testBleScanUnoptimized", atom, key,
                stateOn, stateOff, minTimeDiffMs, maxTimeDiffMs, true);

        BleUnoptimizedScanStateChanged a0 = data.get(0).getAtom().getBleUnoptimizedScanStateChanged();
        BleUnoptimizedScanStateChanged a1 = data.get(1).getAtom().getBleUnoptimizedScanStateChanged();
        assertTrue(a0.getState().getNumber() == stateOn);
        assertTrue(a1.getState().getNumber() == stateOff);

        // Now repeat the test for optimized scanning and make sure no atoms are reported.
        StatsdConfig.Builder conf = createConfigBuilder();
        addAtomEvent(conf, atom, createKvm(key).setEqInt(stateOn));
        addAtomEvent(conf, atom, createKvm(key).setEqInt(stateOff));
        data = doDeviceMethod("testBleScanOptimized", conf);
        assertTrue(data.isEmpty());
    }

    public void testBleScanResult() throws Exception {
        if (!TESTS_ENABLED) return;
        if (!hasFeature(FEATURE_BLUETOOTH_LE, true)) return;
        turnScreenOn(); // BLE results are not given unless screen is on. TODO: make more robust.

        final int atom = Atom.BLE_SCAN_RESULT_RECEIVED_FIELD_NUMBER;
        final int key = BleScanResultReceived.NUM_OF_RESULTS_FIELD_NUMBER;

        StatsdConfig.Builder conf = createConfigBuilder();
        addAtomEvent(conf, atom, createKvm(key).setGteInt(0));
        List<EventMetricData> data = doDeviceMethod("testBleScanResult", conf);

        assertTrue(data.size() >= 1);
        BleScanResultReceived a0 = data.get(0).getAtom().getBleScanResultReceived();
        assertTrue(a0.getNumOfResults() >= 1);

        turnScreenOff();
    }

    public void testGpsScan() throws Exception {
        if (!TESTS_ENABLED) return;
        if (!hasFeature(FEATURE_LOCATION_GPS, true)) return;
        // Whitelist this app against background location request throttling
        getDevice().executeShellCommand(String.format(
                "settings put global location_background_throttle_package_whitelist %s",
                DEVICE_SIDE_TEST_PACKAGE));

        final int atom = Atom.GPS_SCAN_STATE_CHANGED_FIELD_NUMBER;
        final int key = GpsScanStateChanged.STATE_FIELD_NUMBER;
        final int stateOn = GpsScanStateChanged.State.ON_VALUE;
        final int stateOff = GpsScanStateChanged.State.OFF_VALUE;
        final int minTimeDiffMs = 500;
        final int maxTimeDiffMs = 60_000;

        List<EventMetricData> data = doDeviceMethodOnOff("testGpsScan", atom, key,
                stateOn, stateOff, minTimeDiffMs, maxTimeDiffMs, true);

        GpsScanStateChanged a0 = data.get(0).getAtom().getGpsScanStateChanged();
        GpsScanStateChanged a1 = data.get(1).getAtom().getGpsScanStateChanged();
        assertTrue(a0.getState().getNumber() == stateOn);
        assertTrue(a1.getState().getNumber() == stateOff);
    }

    public void testWifiScan() throws Exception {
        if (!TESTS_ENABLED) return;
        if (!hasFeature(FEATURE_WIFI, true)) return;

        final int atom = Atom.WIFI_SCAN_STATE_CHANGED_FIELD_NUMBER;
        final int key = WifiScanStateChanged.STATE_FIELD_NUMBER;
        final int stateOn = WifiScanStateChanged.State.ON_VALUE;
        final int stateOff = WifiScanStateChanged.State.OFF_VALUE;
        final int minTimeDiffMs = 500;
        final int maxTimeDiffMs = 60_000;
        final boolean demandExactlyTwo = false; // Two scans are performed, so up to 4 atoms logged.

        List<EventMetricData> data = doDeviceMethodOnOff("testWifiScan", atom, key,
                stateOn, stateOff, minTimeDiffMs, maxTimeDiffMs, demandExactlyTwo);

        assertTrue(data.size() >= 2);
        assertTrue(data.size() <= 4);
        WifiScanStateChanged a0 = data.get(0).getAtom().getWifiScanStateChanged();
        WifiScanStateChanged a1 = data.get(1).getAtom().getWifiScanStateChanged();
        assertTrue(a0.getState().getNumber() == stateOn);
        assertTrue(a1.getState().getNumber() == stateOff);
    }
}
