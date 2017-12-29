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

import com.android.internal.os.StatsdConfigProto.Alert;
import com.android.internal.os.StatsdConfigProto.Bucket;
import com.android.internal.os.StatsdConfigProto.CountMetric;
import com.android.internal.os.StatsdConfigProto.DurationMetric;
import com.android.internal.os.StatsdConfigProto.FieldFilter;
import com.android.internal.os.StatsdConfigProto.FieldMatcher;
import com.android.internal.os.StatsdConfigProto.GaugeMetric;
import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.internal.os.StatsdConfigProto.ValueMetric;
import com.android.os.AtomsProto.Atom;
import com.android.os.AtomsProto.BatteryLevelChanged;
import com.android.os.AtomsProto.BatterySaverModeStateChanged;
import com.android.os.AtomsProto.ChargingStateChanged;
import com.android.os.AtomsProto.CpuTimePerUid;
import com.android.os.AtomsProto.CpuTimePerUidFreq;
import com.android.os.AtomsProto.DeviceIdleModeStateChanged;
import com.android.os.AtomsProto.KernelWakelock;
import com.android.os.AtomsProto.PlatformSleepState;
import com.android.os.AtomsProto.PluggedStateChanged;
import com.android.os.AtomsProto.ScreenBrightnessChanged;
import com.android.os.AtomsProto.ScreenStateChanged;
import com.android.os.AtomsProto.SleepStateVoter;
import com.android.os.AtomsProto.SubsystemSleepState;
import com.android.os.StatsLog.EventMetricData;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Statsd atom tests that are done via adb (hostside).
 */
public class HostAtomTests extends AtomTestCase {

    private static final String TAG = "Statsd.HostAtomTests";

    private static final boolean TESTS_ENABLED = false;
    // For tests that require incidentd. Keep as true until TESTS_ENABLED is permanently enabled.
    private static final boolean INCIDENTD_TESTS_ENABLED = false;

    private static final String TEST_CONFIG_NAME = "cts_test_config";

    public void testScreenStateChangedAtom() throws Exception {
        if (!TESTS_ENABLED) {return;}

        // Setup, make sure the screen is off.
        turnScreenOff();
        Thread.sleep(2000);

        final int atomTag = Atom.SCREEN_STATE_CHANGED_FIELD_NUMBER;
        final int key = ScreenStateChanged.DISPLAY_STATE_FIELD_NUMBER;
        Set<Integer> screenOnStates = new HashSet<>(
                Arrays.asList(ScreenStateChanged.State.STATE_ON_VALUE,
                              ScreenStateChanged.State.STATE_ON_SUSPEND_VALUE,
                              ScreenStateChanged.State.STATE_VR_VALUE));
        Set<Integer> screenOffStates = new HashSet<>(
                Arrays.asList(ScreenStateChanged.State.STATE_OFF_VALUE,
                              ScreenStateChanged.State.STATE_DOZE_VALUE,
                              ScreenStateChanged.State.STATE_DOZE_SUSPEND_VALUE,
                              ScreenStateChanged.State.STATE_UNKNOWN_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(screenOnStates, screenOffStates);

        createAndUploadConfig(atomTag);
        Thread.sleep(2000);

        // Trigger events in same order.
        turnScreenOn();
        Thread.sleep(2000);
        turnScreenOff();
        Thread.sleep(2000);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Assert that the events happened in the expected order.
        assertStatesOccurred(
                stateSet, data, atom -> atom.getScreenStateChanged().getDisplayState().getNumber());
    }

    public void testChargingStateChangedAtom() throws Exception {
        if (!TESTS_ENABLED) {return;}

        // Setup, set charging state to full.
        setChargingState(5);
        Thread.sleep(2000);

        final int atomTag = Atom.CHARGING_STATE_CHANGED_FIELD_NUMBER;
        final int key = ChargingStateChanged.CHARGING_STATE_FIELD_NUMBER;
        Set<Integer> batteryUnknownStates = new HashSet<>(
            Arrays.asList(ChargingStateChanged.State.BATTERY_STATUS_UNKNOWN_VALUE));
        Set<Integer> batteryChargingStates = new HashSet<>(
            Arrays.asList(ChargingStateChanged.State.BATTERY_STATUS_CHARGING_VALUE));
        Set<Integer> batteryDischargingStates = new HashSet<>(
            Arrays.asList(ChargingStateChanged.State.BATTERY_STATUS_DISCHARGING_VALUE));
        Set<Integer> batteryNotChargingStates = new HashSet<>(
            Arrays.asList(ChargingStateChanged.State.BATTERY_STATUS_NOT_CHARGING_VALUE));
        Set<Integer> batteryFullStates = new HashSet<>(
            Arrays.asList(ChargingStateChanged.State.BATTERY_STATUS_FULL_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(batteryUnknownStates, batteryChargingStates,
                batteryDischargingStates, batteryNotChargingStates, batteryFullStates);

        createAndUploadConfig(atomTag);
        Thread.sleep(2000);

        // Trigger events in same order.
        setChargingState(1);
        Thread.sleep(2000);
        setChargingState(2);
        Thread.sleep(2000);
        setChargingState(3);
        Thread.sleep(2000);
        setChargingState(4);
        Thread.sleep(2000);
        setChargingState(5);
        Thread.sleep(2000);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Unfreeze battery state after test
        resetBatteryStatus();
        Thread.sleep(2000);

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data,
                atom -> atom.getChargingStateChanged().getChargingState().getNumber());
    }

    public void testPluggedStateChangedAtom() throws Exception {
        if (!TESTS_ENABLED) {return;}

        // Setup, unplug device.
        unplugDevice();
        Thread.sleep(2000);

        final int atomTag = Atom.PLUGGED_STATE_CHANGED_FIELD_NUMBER;
        final int key = PluggedStateChanged.PLUGGED_STATE_FIELD_NUMBER;
        Set<Integer> unpluggedStates = new HashSet<>(
            Arrays.asList(PluggedStateChanged.State.BATTERY_PLUGGED_NONE_VALUE));
        Set<Integer> acStates = new HashSet<>(
            Arrays.asList(PluggedStateChanged.State.BATTERY_PLUGGED_AC_VALUE));
        Set<Integer> usbStates = new HashSet<>(
            Arrays.asList(PluggedStateChanged.State.BATTERY_PLUGGED_USB_VALUE));
        Set<Integer> wirelessStates = new HashSet<>(
            Arrays.asList(PluggedStateChanged.State.BATTERY_PLUGGED_WIRELESS_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(acStates, unpluggedStates, usbStates,
                unpluggedStates, wirelessStates, unpluggedStates);

        createAndUploadConfig(atomTag);
        Thread.sleep(2000);

        // Trigger events in same order.
        plugInAc();
        Thread.sleep(2000);
        unplugDevice();
        Thread.sleep(2000);
        plugInUsb();
        Thread.sleep(2000);
        unplugDevice();
        Thread.sleep(2000);
        plugInWireless();
        Thread.sleep(2000);
        unplugDevice();
        Thread.sleep(2000);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Unfreeze battery state after test
        resetBatteryStatus();
        Thread.sleep(2000);

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data,
            atom -> atom.getPluggedStateChanged().getPluggedState().getNumber());
    }

    public void testBatteryLevelChangedAtom() throws Exception {
        if (!TESTS_ENABLED) {return;}

        // Setup, set battery level to full.
        setBatteryLevel(100);
        Thread.sleep(2000);

        final int atomTag = Atom.BATTERY_LEVEL_CHANGED_FIELD_NUMBER;
        final int key = BatteryLevelChanged.BATTERY_LEVEL_FIELD_NUMBER;
        Set<Integer> batteryDead = new HashSet<>(Arrays.asList(0));
        Set<Integer> battery25p = new HashSet<>(Arrays.asList(25));
        Set<Integer> battery50p = new HashSet<>(Arrays.asList(50));
        Set<Integer> battery75p = new HashSet<>(Arrays.asList(75));
        Set<Integer> batteryFull = new HashSet<>(Arrays.asList(100));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(batteryDead, battery25p, battery50p,
                battery75p, batteryFull);

        createAndUploadConfig(atomTag);
        Thread.sleep(2000);

        // Trigger events in same order.
        setBatteryLevel(0);
        Thread.sleep(2000);
        setBatteryLevel(25);
        Thread.sleep(2000);
        setBatteryLevel(50);
        Thread.sleep(2000);
        setBatteryLevel(75);
        Thread.sleep(2000);
        setBatteryLevel(100);
        Thread.sleep(2000);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Unfreeze battery state after test
        resetBatteryStatus();
        Thread.sleep(2000);

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data,
                atom -> atom.getBatteryLevelChanged().getBatteryLevel());
    }

    public void testScreenBrightnessChangedAtom() throws Exception {
        if (!TESTS_ENABLED) {return;}

        // Setup: record initial brightness state, set mode to manual and brightness to full.
        int initialBrightness = getScreenBrightness();
        boolean isInitialManual = isScreenBrightnessModeManual();
        int initialTimeout = getScreenTimeoutMs();
        setScreenTimeoutMs(600000);
        turnScreenOn();
        setScreenBrightnessMode(true);
        setScreenBrightness(255);
        Thread.sleep(2000);

        final int atomTag = Atom.SCREEN_BRIGHTNESS_CHANGED_FIELD_NUMBER;
        final int key = ScreenBrightnessChanged.LEVEL_FIELD_NUMBER;
        Set<Integer> screenMin = new HashSet<>(Arrays.asList(25));
        Set<Integer> screen100 = new HashSet<>(Arrays.asList(100));
        Set<Integer> screen200 = new HashSet<>(Arrays.asList(200));
        Set<Integer> screenMax = new HashSet<>(Arrays.asList(255));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(screenMin, screen100, screen200, screenMax);

        createAndUploadConfig(atomTag);
        Thread.sleep(2000);

        // Trigger events in same order.
        setScreenBrightness(25);
        Thread.sleep(2000);
        setScreenBrightness(100);
        Thread.sleep(2000);
        setScreenBrightness(200);
        Thread.sleep(2000);
        setScreenBrightness(255);
        Thread.sleep(2000);


        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Restore initial screen brightness
        setScreenBrightness(initialBrightness);
        setScreenBrightnessMode(isInitialManual);
        setScreenTimeoutMs(initialTimeout);
        turnScreenOff();
        Thread.sleep(2000);

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, atom -> atom.getScreenBrightnessChanged().getLevel());
    }

    public void testDeviceIdleModeStateChangedAtom() throws Exception {
        if (!TESTS_ENABLED) {return;}

        // Setup, leave doze mode.
        leaveDozeMode();
        Thread.sleep(2000);

        final int atomTag = Atom.DEVICE_IDLE_MODE_STATE_CHANGED_FIELD_NUMBER;
        final int key = DeviceIdleModeStateChanged.STATE_FIELD_NUMBER;
        Set<Integer> dozeOff = new HashSet<>(
            Arrays.asList(DeviceIdleModeStateChanged.State.DEVICE_IDLE_MODE_OFF_VALUE));
        Set<Integer> dozeLight = new HashSet<>(
            Arrays.asList(DeviceIdleModeStateChanged.State.DEVICE_IDLE_MODE_LIGHT_VALUE));
        Set<Integer> dozeDeep = new HashSet<>(
            Arrays.asList(DeviceIdleModeStateChanged.State.DEVICE_IDLE_MODE_DEEP_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(dozeLight, dozeDeep, dozeOff);

        createAndUploadConfig(atomTag);
        Thread.sleep(2000);

        // Trigger events in same order.
        enterDozeModeLight();
        Thread.sleep(2000);
        enterDozeModeDeep();
        Thread.sleep(2000);
        leaveDozeMode();
        Thread.sleep(2000);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();;

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data,
            atom -> atom.getDeviceIdleModeStateChanged().getState().getNumber());
    }

    public void testBatterySaverModeStateChangedAtom() throws Exception {
        if (!TESTS_ENABLED) {return;}

        // Setup, turn off battery saver.
        turnBatterySaverOff();
        Thread.sleep(2000);

        final int atomTag = Atom.BATTERY_SAVER_MODE_STATE_CHANGED_FIELD_NUMBER;
        final int key = BatterySaverModeStateChanged.STATE_FIELD_NUMBER;
        Set<Integer> batterySaverOn = new HashSet<>(
            Arrays.asList(BatterySaverModeStateChanged.State.ON_VALUE));
        Set<Integer> batterySaverOff = new HashSet<>(
            Arrays.asList(BatterySaverModeStateChanged.State.OFF_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(batterySaverOn, batterySaverOff);

        createAndUploadConfig(atomTag);
        Thread.sleep(2000);

        // Trigger events in same order.
        turnBatterySaverOn();
        Thread.sleep(2000);
        turnBatterySaverOff();
        Thread.sleep(2000);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data,
            atom -> atom.getBatterySaverModeStateChanged().getState().getNumber());
    }

    // TODO: Anomaly detection will be moved to general statsd device-side tests.
    // Tests that anomaly detection for count works.
    // Also tests that anomaly detection works when spanning multiple buckets.
    public void testCountAnomalyDetection() throws Exception {
        if (!TESTS_ENABLED) return;
        if (!INCIDENTD_TESTS_ENABLED) return;
        // TODO: Don't use screen-state as the atom.
        StatsdConfig config = getPulledAndAnomalyConfig()
                .addCountMetric(CountMetric.newBuilder()
                    .setName("METRIC")
                    .setWhat("SCREEN_TURNED_ON")
                    .setBucket(Bucket.newBuilder().setBucketSizeMillis(5_000))
                )
                .addAlert(Alert.newBuilder()
                    .setName("testCountAnomalyDetectionAlert")
                    .setMetricName("METRIC")
                    .setNumberOfBuckets(4)
                    .setRefractoryPeriodSecs(20)
                    .setTriggerIfSumGt(2)
                    .setIncidentdDetails(Alert.IncidentdDetails.newBuilder()
                        .addSection(-1)
                    )
                )
                .build();
        uploadConfig(config);

        String markDeviceDate = getCurrentLogcatDate();
        turnScreenOn(); // count -> 1 (not an anomaly, since not "greater than 2")
        Thread.sleep(1000);
        turnScreenOff();
        Thread.sleep(3000);
        assertFalse(didIncidentdFireSince(markDeviceDate));

        turnScreenOn(); // count ->2 (not an anomaly, since not "greater than 2")
        Thread.sleep(1000);
        turnScreenOff();
        Thread.sleep(1000);
        assertFalse(didIncidentdFireSince(markDeviceDate));

        turnScreenOn(); // count ->3 (anomaly, since "greater than 2"!)
        Thread.sleep(1000);
        assertTrue(didIncidentdFireSince(markDeviceDate));

        turnScreenOff();
    }

    // Tests that anomaly detection for duration works.
    // Also tests that refractory periods in anomaly detection work.
    public void testDurationAnomalyDetection() throws Exception {
        if (!TESTS_ENABLED) return;
        if (!INCIDENTD_TESTS_ENABLED) return;
        // TODO: Do NOT use screenState for this, since screens auto-turn-off after a variable time.
        StatsdConfig config = getPulledAndAnomalyConfig()
                .addDurationMetric(DurationMetric.newBuilder()
                        .setName("METRIC")
                        .setWhat("SCREEN_IS_ON")
                        .setAggregationType(DurationMetric.AggregationType.SUM)
                        .setBucket(Bucket.newBuilder().setBucketSizeMillis(5_000))
                )
                .addAlert(Alert.newBuilder()
                        .setName("testDurationAnomalyDetectionAlert")
                        .setMetricName("METRIC")
                        .setNumberOfBuckets(12)
                        .setRefractoryPeriodSecs(20)
                        .setTriggerIfSumGt(15_000_000_000L) // 15 seconds in nanoseconds
                        .setIncidentdDetails(Alert.IncidentdDetails.newBuilder()
                                .addSection(-1)
                        )
                )
                .build();
        uploadConfig(config);

        // Test that alarm doesn't fire early.
        String markDeviceDate = getCurrentLogcatDate();
        turnScreenOn();
        Thread.sleep(6_000);
        assertFalse(didIncidentdFireSince(markDeviceDate));

        turnScreenOff();
        Thread.sleep(1_000);
        assertFalse(didIncidentdFireSince(markDeviceDate));

        // Test that alarm does fire when it is supposed to.
        turnScreenOn();
        Thread.sleep(13_000);
        assertTrue(didIncidentdFireSince(markDeviceDate));

        // Now test that the refractory period is obeyed.
        markDeviceDate = getCurrentLogcatDate();
        turnScreenOff();
        Thread.sleep(1_000);
        turnScreenOn();
        Thread.sleep(1_000);
        assertFalse(didIncidentdFireSince(markDeviceDate));

        // Test that detection works again after refractory period finishes.
        turnScreenOff();
        Thread.sleep(20_000);
        turnScreenOn();
        Thread.sleep(15_000);
        assertTrue(didIncidentdFireSince(markDeviceDate));
    }

    // TODO: There is no value anomaly detection code yet! So this will fail.
    // Tests that anomaly detection for value works.
    public void testValueAnomalyDetection() throws Exception {
        if (!TESTS_ENABLED) return;
        if (!INCIDENTD_TESTS_ENABLED) return;
        // TODO: Definitely don't use screen-state as the atom. This MUST be changed.
        StatsdConfig config = getPulledAndAnomalyConfig()
                .addValueMetric(ValueMetric.newBuilder()
                        .setName("METRIC")
                        .setWhat("SCREEN_TURNED_ON")
                        .setValueField(ScreenStateChanged.DISPLAY_STATE_FIELD_NUMBER)
                        .setBucket(Bucket.newBuilder().setBucketSizeMillis(5_000))
                )
                .addAlert(Alert.newBuilder()
                        .setName("testValueAnomalyDetectionAlert")
                        .setMetricName("METRIC")
                        .setNumberOfBuckets(4)
                        .setRefractoryPeriodSecs(20)
                        .setTriggerIfSumGt(ScreenStateChanged.State.STATE_OFF.getNumber())
                        .setIncidentdDetails(Alert.IncidentdDetails.newBuilder()
                                .addSection(-1)
                        )
                )
                .build();
        uploadConfig(config);

        turnScreenOff();
        String markDeviceDate = getCurrentLogcatDate();
        turnScreenOff(); // value = STATE_OFF = 1 (probably)
        Thread.sleep(2000);
        assertFalse(didIncidentdFireSince(markDeviceDate));
        turnScreenOn(); // value = STATE_ON = 2 (probably)
        Thread.sleep(2000);
        assertTrue(didIncidentdFireSince(markDeviceDate));

        turnScreenOff();
    }

    // Tests that anomaly detection for gauge works.
    public void testGaugeAnomalyDetection() throws Exception {
        if (!TESTS_ENABLED) return;
        if (!INCIDENTD_TESTS_ENABLED) return;
        // TODO: Definitely don't use screen-state as the atom. This MUST be changed.
        StatsdConfig config = getPulledAndAnomalyConfig()
                .addGaugeMetric(GaugeMetric.newBuilder()
                        .setName("METRIC")
                        .setWhat("SCREEN_TURNED_ON")
                        .setGaugeFieldsFilter(
                                FieldFilter.newBuilder()
                                        .setFields(FieldMatcher.newBuilder()
                                                .setField(Atom.SCREEN_STATE_CHANGED_FIELD_NUMBER)
                                                .addChild(FieldMatcher.newBuilder()
                                                        .setField(ScreenStateChanged.DISPLAY_STATE_FIELD_NUMBER))
                                ))
                        .setBucket(Bucket.newBuilder().setBucketSizeMillis(10_000))
                )
                .addAlert(Alert.newBuilder()
                        .setName("testGaugeAnomalyDetectionAlert")
                        .setMetricName("METRIC")
                        .setNumberOfBuckets(1)
                        .setRefractoryPeriodSecs(20)
                        .setTriggerIfSumGt(ScreenStateChanged.State.STATE_OFF.getNumber())
                        .setIncidentdDetails(Alert.IncidentdDetails.newBuilder()
                                .addSection(-1)
                        )
                )
                .build();
        uploadConfig(config);

        turnScreenOff();
        String markDeviceDate = getCurrentLogcatDate();
        turnScreenOff(); // gauge = STATE_OFF = 1 (probably)
        Thread.sleep(2000);
        assertFalse(didIncidentdFireSince(markDeviceDate));
        turnScreenOn(); // gauge = STATE_ON = 2 (probably)
        Thread.sleep(2000);
        assertTrue(didIncidentdFireSince(markDeviceDate));

        turnScreenOff();
    }

    public void testKernelWakelock() throws Exception {
        if (!TESTS_ENABLED) {return;}
        StatsdConfig.Builder config = getPulledAndAnomalyConfig();
        FieldMatcher.Builder dimension = FieldMatcher.newBuilder()
                .setField(Atom.KERNEL_WAKELOCK_FIELD_NUMBER)
                .addChild(FieldMatcher.newBuilder()
                        .setField(KernelWakelock.NAME_FIELD_NUMBER));
        addGaugeAtom(config, Atom.KERNEL_WAKELOCK_FIELD_NUMBER, dimension);

        turnScreenOff();

        uploadConfig(config);

        Thread.sleep(2000);
        turnScreenOn();
        Thread.sleep(2000);

        List<Atom> data = getGaugeMetricDataList();

        Atom atom = data.get(0);
        assertTrue(!atom.getKernelWakelock().getName().equals(""));
        assertTrue(atom.getKernelWakelock().hasCount());
        assertTrue(atom.getKernelWakelock().hasVersion());
        assertTrue(atom.getKernelWakelock().getVersion() > 0);
        assertTrue(atom.getKernelWakelock().hasTime());
    }

    public void testCpuTimePerUidFreq() throws Exception {
        if (!TESTS_ENABLED) {return;}
        StatsdConfig.Builder config = getPulledAndAnomalyConfig();
        FieldMatcher.Builder dimension = FieldMatcher.newBuilder()
                .setField(Atom.CPU_TIME_PER_UID_FREQ_FIELD_NUMBER)
                .addChild(FieldMatcher.newBuilder()
                        .setField(CpuTimePerUidFreq.UID_FIELD_NUMBER));
        addGaugeAtom(config, Atom.CPU_TIME_PER_UID_FREQ_FIELD_NUMBER, dimension);

        turnScreenOff();

        uploadConfig(config);

        Thread.sleep(2000);
        turnScreenOn();
        Thread.sleep(2000);

        List<Atom> data = getGaugeMetricDataList();

        Atom atom = data.get(0);
        assertTrue(atom.getCpuTimePerUidFreq().getUid() > 0);
        assertTrue(atom.getCpuTimePerUidFreq().getFreqIdx() >= 0);
        assertTrue(atom.getCpuTimePerUidFreq().getTimeMs() > 0);
    }

    public void testCpuTimePerUid() throws Exception {
        if (!TESTS_ENABLED) {return;}
        StatsdConfig.Builder config = getPulledAndAnomalyConfig();
        FieldMatcher.Builder dimension = FieldMatcher.newBuilder()
                .setField(Atom.CPU_TIME_PER_UID_FIELD_NUMBER)
                .addChild(FieldMatcher.newBuilder()
                        .setField(CpuTimePerUid.UID_FIELD_NUMBER));
        addGaugeAtom(config, Atom.CPU_TIME_PER_UID_FIELD_NUMBER, dimension);

        turnScreenOff();

        uploadConfig(config);

        Thread.sleep(2000);
        turnScreenOn();
        Thread.sleep(2000);

        List<Atom> data = getGaugeMetricDataList();

        Atom atom = data.get(0);
        assertTrue(atom.getCpuTimePerUid().getUid() > 0);
        assertTrue(atom.getCpuTimePerUid().getUserTimeMs() > 0);
        assertTrue(atom.getCpuTimePerUid().getSysTimeMs() > 0);
    }

    public void testPlatformSleepState() throws Exception {
        if (!TESTS_ENABLED) {return;}
        StatsdConfig.Builder config = getPulledAndAnomalyConfig();
        FieldMatcher.Builder dimension = FieldMatcher.newBuilder()
                .setField(Atom.PLATFORM_SLEEP_STATE_FIELD_NUMBER)
                .addChild(FieldMatcher.newBuilder()
                        .setField(PlatformSleepState.NAME_FIELD_NUMBER));
        addGaugeAtom(config, Atom.PLATFORM_SLEEP_STATE_FIELD_NUMBER, dimension);

        turnScreenOff();

        uploadConfig(config);

        Thread.sleep(2000);
        turnScreenOn();
        Thread.sleep(2000);

        List<Atom> data = getGaugeMetricDataList();

        Atom atom = data.get(0);
        assertTrue(!atom.getPlatformSleepState().getName().equals(""));
    }

    public void testSleepStateVoter() throws Exception {
        if (!TESTS_ENABLED) {return;}
        StatsdConfig.Builder config = getPulledAndAnomalyConfig();
        FieldMatcher.Builder dimension = FieldMatcher.newBuilder()
                .setField(Atom.SLEEP_STATE_VOTER_FIELD_NUMBER)
                .addChild(FieldMatcher.newBuilder()
                        .setField(SleepStateVoter.VOTER_NAME_FIELD_NUMBER));
        addGaugeAtom(config, Atom.SLEEP_STATE_VOTER_FIELD_NUMBER, dimension);

        turnScreenOff();

        uploadConfig(config);

        Thread.sleep(2000);
        turnScreenOn();
        Thread.sleep(2000);

        List<Atom> data = getGaugeMetricDataList();

        Atom atom = data.get(0);
        assertTrue(!atom.getSleepStateVoter().getPlatformSleepStateName().equals(""));
        assertTrue(!atom.getSleepStateVoter().getVoterName().equals(""));
    }

    public void testSubsystemSleepState() throws Exception {
        if (!TESTS_ENABLED) {return;}
        StatsdConfig.Builder config = getPulledAndAnomalyConfig();
        FieldMatcher.Builder dimension = FieldMatcher.newBuilder()
                .setField(Atom.SUBSYSTEM_SLEEP_STATE_FIELD_NUMBER)
                .addChild(FieldMatcher.newBuilder()
                        .setField(SubsystemSleepState.SUBSYSTEM_NAME_FIELD_NUMBER));
        addGaugeAtom(config, Atom.SUBSYSTEM_SLEEP_STATE_FIELD_NUMBER, dimension);

        turnScreenOff();

        uploadConfig(config);

        Thread.sleep(2000);
        turnScreenOn();
        Thread.sleep(2000);

        List<Atom> data = getGaugeMetricDataList();

        Atom atom = data.get(0);
        assertTrue(!atom.getSubsystemSleepState().getSubsystemName().equals(""));
        assertTrue(!atom.getSubsystemSleepState().getSubsystemSleepStateName().equals(""));
    }

    /**
     * TODO: Anomaly detection will be moved to general statsd device-side tests.
     * Pulled atoms also should have a better way of constructing the config.
     * Remove this config when that happens.
     */
    protected StatsdConfig.Builder getPulledAndAnomalyConfig() {
        return StatsdConfig.newBuilder().setName(TEST_CONFIG_NAME);
    }
}
