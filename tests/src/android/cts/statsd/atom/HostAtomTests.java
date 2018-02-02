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
package android.cts.statsd.atom;

import android.os.BatteryPluggedStateEnum; // From os/enums.proto
import android.os.BatteryStatusEnum; // From os/enums.proto
import android.platform.test.annotations.RestrictedBuildTest;
import android.server.DeviceIdleModeEnum; // From server/enums.proto
import android.view.DisplayStateEnum; // From view/enums.proto

import com.android.internal.os.StatsdConfigProto.Alert;
import com.android.internal.os.StatsdConfigProto.CountMetric;
import com.android.internal.os.StatsdConfigProto.DurationMetric;
import com.android.internal.os.StatsdConfigProto.FieldFilter;
import com.android.internal.os.StatsdConfigProto.FieldMatcher;
import com.android.internal.os.StatsdConfigProto.GaugeMetric;
import com.android.internal.os.StatsdConfigProto.IncidentdDetails;
import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.internal.os.StatsdConfigProto.Subscription;
import com.android.internal.os.StatsdConfigProto.TimeUnit;
import com.android.internal.os.StatsdConfigProto.ValueMetric;
import com.android.os.AtomsProto.Atom;
import com.android.os.AtomsProto.BatterySaverModeStateChanged;
import com.android.os.AtomsProto.ChargingStateChanged;
import com.android.os.AtomsProto.CpuTimePerFreq;
import com.android.os.AtomsProto.DeviceIdleModeStateChanged;
import com.android.os.AtomsProto.FullBatteryCapacity;
import com.android.os.AtomsProto.KernelWakelock;
import com.android.os.AtomsProto.PluggedStateChanged;
import com.android.os.AtomsProto.RemainingBatteryCapacity;
import com.android.os.AtomsProto.ScreenStateChanged;
import com.android.os.AtomsProto.SubsystemSleepState;
import com.android.os.StatsLog.EventMetricData;
import com.android.tradefed.log.LogUtil.CLog;

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
    private static final boolean INCIDENTD_TESTS_ENABLED = true;

    private static final String FEATURE_BLUETOOTH = "android.hardware.bluetooth";
    private static final String FEATURE_WIFI = "android.hardware.wifi";
    private static final String FEATURE_TELEPHONY = "android.hardware.telephony";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (!TESTS_ENABLED) {
            CLog.w(TAG, TAG + " tests are disabled by a flag. Change flag to true to run.");
        }
    }

    public void testScreenStateChangedAtom() throws Exception {
        if (!TESTS_ENABLED) {return;}

        // Setup, make sure the screen is off.
        turnScreenOff();
        Thread.sleep(WAIT_TIME_LONG);

        final int atomTag = Atom.SCREEN_STATE_CHANGED_FIELD_NUMBER;

        Set<Integer> screenOnStates = new HashSet<>(
                Arrays.asList(DisplayStateEnum.DISPLAY_STATE_ON_VALUE,
                        DisplayStateEnum.DISPLAY_STATE_ON_SUSPEND_VALUE,
                        DisplayStateEnum.DISPLAY_STATE_VR_VALUE));
        Set<Integer> screenOffStates = new HashSet<>(
                Arrays.asList(DisplayStateEnum.DISPLAY_STATE_OFF_VALUE,
                        DisplayStateEnum.DISPLAY_STATE_DOZE_VALUE,
                        DisplayStateEnum.DISPLAY_STATE_DOZE_SUSPEND_VALUE,
                        DisplayStateEnum.DISPLAY_STATE_UNKNOWN_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(screenOnStates, screenOffStates);

        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);

        // Trigger events in same order.
        turnScreenOn();
        Thread.sleep(WAIT_TIME_LONG);
        turnScreenOff();
        Thread.sleep(WAIT_TIME_LONG);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_LONG,
                atom -> atom.getScreenStateChanged().getState().getNumber());
    }

    public void testChargingStateChangedAtom() throws Exception {
        if (!TESTS_ENABLED) {return;}

        // Setup, set charging state to full.
        setChargingState(5);
        Thread.sleep(WAIT_TIME_SHORT);

        final int atomTag = Atom.CHARGING_STATE_CHANGED_FIELD_NUMBER;

        Set<Integer> batteryUnknownStates = new HashSet<>(
                Arrays.asList(BatteryStatusEnum.BATTERY_STATUS_UNKNOWN_VALUE));
        Set<Integer> batteryChargingStates = new HashSet<>(
                Arrays.asList(BatteryStatusEnum.BATTERY_STATUS_CHARGING_VALUE));
        Set<Integer> batteryDischargingStates = new HashSet<>(
                Arrays.asList(BatteryStatusEnum.BATTERY_STATUS_DISCHARGING_VALUE));
        Set<Integer> batteryNotChargingStates = new HashSet<>(
                Arrays.asList(BatteryStatusEnum.BATTERY_STATUS_NOT_CHARGING_VALUE));
        Set<Integer> batteryFullStates = new HashSet<>(
                Arrays.asList(BatteryStatusEnum.BATTERY_STATUS_FULL_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(batteryUnknownStates, batteryChargingStates,
                batteryDischargingStates, batteryNotChargingStates, batteryFullStates);

        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);

        // Trigger events in same order.
        setChargingState(1);
        Thread.sleep(WAIT_TIME_SHORT);
        setChargingState(2);
        Thread.sleep(WAIT_TIME_SHORT);
        setChargingState(3);
        Thread.sleep(WAIT_TIME_SHORT);
        setChargingState(4);
        Thread.sleep(WAIT_TIME_SHORT);
        setChargingState(5);
        Thread.sleep(WAIT_TIME_SHORT);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Unfreeze battery state after test
        resetBatteryStatus();
        Thread.sleep(WAIT_TIME_SHORT);

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
                atom -> atom.getChargingStateChanged().getState().getNumber());
    }

    public void testPluggedStateChangedAtom() throws Exception {
        if (!TESTS_ENABLED) {return;}

        // Setup, unplug device.
        unplugDevice();
        Thread.sleep(WAIT_TIME_SHORT);

        final int atomTag = Atom.PLUGGED_STATE_CHANGED_FIELD_NUMBER;

        Set<Integer> unpluggedStates = new HashSet<>(
                Arrays.asList(BatteryPluggedStateEnum.BATTERY_PLUGGED_NONE_VALUE));
        Set<Integer> acStates = new HashSet<>(
                Arrays.asList(BatteryPluggedStateEnum.BATTERY_PLUGGED_AC_VALUE));
        Set<Integer> usbStates = new HashSet<>(
                Arrays.asList(BatteryPluggedStateEnum.BATTERY_PLUGGED_USB_VALUE));
        Set<Integer> wirelessStates = new HashSet<>(
                Arrays.asList(BatteryPluggedStateEnum.BATTERY_PLUGGED_WIRELESS_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(acStates, unpluggedStates, usbStates,
                unpluggedStates, wirelessStates, unpluggedStates);

        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);

        // Trigger events in same order.
        plugInAc();
        Thread.sleep(WAIT_TIME_SHORT);
        unplugDevice();
        Thread.sleep(WAIT_TIME_SHORT);
        plugInUsb();
        Thread.sleep(WAIT_TIME_SHORT);
        unplugDevice();
        Thread.sleep(WAIT_TIME_SHORT);
        plugInWireless();
        Thread.sleep(WAIT_TIME_SHORT);
        unplugDevice();
        Thread.sleep(WAIT_TIME_SHORT);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Unfreeze battery state after test
        resetBatteryStatus();
        Thread.sleep(WAIT_TIME_SHORT);

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
                atom -> atom.getPluggedStateChanged().getState().getNumber());
    }

    public void testBatteryLevelChangedAtom() throws Exception {
        if (!TESTS_ENABLED) {return;}

        // Setup, set battery level to full.
        setBatteryLevel(100);
        Thread.sleep(WAIT_TIME_SHORT);

        final int atomTag = Atom.BATTERY_LEVEL_CHANGED_FIELD_NUMBER;

        Set<Integer> batteryDead = new HashSet<>(Arrays.asList(0));
        Set<Integer> battery25p = new HashSet<>(Arrays.asList(25));
        Set<Integer> battery50p = new HashSet<>(Arrays.asList(50));
        Set<Integer> battery75p = new HashSet<>(Arrays.asList(75));
        Set<Integer> batteryFull = new HashSet<>(Arrays.asList(100));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(batteryDead, battery25p, battery50p,
                battery75p, batteryFull);

        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);

        // Trigger events in same order.
        setBatteryLevel(0);
        Thread.sleep(WAIT_TIME_SHORT);
        setBatteryLevel(25);
        Thread.sleep(WAIT_TIME_SHORT);
        setBatteryLevel(50);
        Thread.sleep(WAIT_TIME_SHORT);
        setBatteryLevel(75);
        Thread.sleep(WAIT_TIME_SHORT);
        setBatteryLevel(100);
        Thread.sleep(WAIT_TIME_SHORT);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Unfreeze battery state after test
        resetBatteryStatus();
        Thread.sleep(WAIT_TIME_SHORT);

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
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
        Thread.sleep(WAIT_TIME_SHORT);

        final int atomTag = Atom.SCREEN_BRIGHTNESS_CHANGED_FIELD_NUMBER;

        Set<Integer> screenMin = new HashSet<>(Arrays.asList(25));
        Set<Integer> screen100 = new HashSet<>(Arrays.asList(100));
        Set<Integer> screen200 = new HashSet<>(Arrays.asList(200));
        Set<Integer> screenMax = new HashSet<>(Arrays.asList(255));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(screenMin, screen100, screen200, screenMax);

        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);

        // Trigger events in same order.
        setScreenBrightness(25);
        Thread.sleep(WAIT_TIME_SHORT);
        setScreenBrightness(100);
        Thread.sleep(WAIT_TIME_SHORT);
        setScreenBrightness(200);
        Thread.sleep(WAIT_TIME_SHORT);
        setScreenBrightness(255);
        Thread.sleep(WAIT_TIME_SHORT);


        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Restore initial screen brightness
        setScreenBrightness(initialBrightness);
        setScreenBrightnessMode(isInitialManual);
        setScreenTimeoutMs(initialTimeout);
        turnScreenOff();
        Thread.sleep(WAIT_TIME_SHORT);

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
                atom -> atom.getScreenBrightnessChanged().getLevel());
    }

    public void testDeviceIdleModeStateChangedAtom() throws Exception {
        if (!TESTS_ENABLED) {return;}

        // Setup, leave doze mode.
        leaveDozeMode();
        Thread.sleep(WAIT_TIME_SHORT);

        final int atomTag = Atom.DEVICE_IDLE_MODE_STATE_CHANGED_FIELD_NUMBER;

        Set<Integer> dozeOff = new HashSet<>(
                Arrays.asList(DeviceIdleModeEnum.DEVICE_IDLE_MODE_OFF_VALUE));
        Set<Integer> dozeLight = new HashSet<>(
                Arrays.asList(DeviceIdleModeEnum.DEVICE_IDLE_MODE_LIGHT_VALUE));
        Set<Integer> dozeDeep = new HashSet<>(
                Arrays.asList(DeviceIdleModeEnum.DEVICE_IDLE_MODE_DEEP_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(dozeLight, dozeDeep, dozeOff);

        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);

        // Trigger events in same order.
        enterDozeModeLight();
        Thread.sleep(WAIT_TIME_SHORT);
        enterDozeModeDeep();
        Thread.sleep(WAIT_TIME_SHORT);
        leaveDozeMode();
        Thread.sleep(WAIT_TIME_SHORT);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();;

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
                atom -> atom.getDeviceIdleModeStateChanged().getState().getNumber());
    }

    public void testBatterySaverModeStateChangedAtom() throws Exception {
        if (!TESTS_ENABLED) {return;}

        // Setup, turn off battery saver.
        turnBatterySaverOff();
        Thread.sleep(WAIT_TIME_SHORT);

        final int atomTag = Atom.BATTERY_SAVER_MODE_STATE_CHANGED_FIELD_NUMBER;

        Set<Integer> batterySaverOn = new HashSet<>(
                Arrays.asList(BatterySaverModeStateChanged.State.ON_VALUE));
        Set<Integer> batterySaverOff = new HashSet<>(
                Arrays.asList(BatterySaverModeStateChanged.State.OFF_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(batterySaverOn, batterySaverOff);

        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);

        // Trigger events in same order.
        turnBatterySaverOn();
        Thread.sleep(WAIT_TIME_SHORT);
        turnBatterySaverOff();
        Thread.sleep(WAIT_TIME_SHORT);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
                atom -> atom.getBatterySaverModeStateChanged().getState().getNumber());
    }

    @RestrictedBuildTest
    public void testRemainingBatteryCapacity() throws Exception {
        if (!TESTS_ENABLED) {return;}
        StatsdConfig.Builder config = getPulledAndAnomalyConfig();
        FieldMatcher.Builder dimension = FieldMatcher.newBuilder()
            .setField(Atom.REMAINING_BATTERY_CAPACITY_FIELD_NUMBER)
            .addChild(FieldMatcher.newBuilder()
                .setField(RemainingBatteryCapacity.CHARGE_UAH_FIELD_NUMBER));
        addGaugeAtom(config, Atom.REMAINING_BATTERY_CAPACITY_FIELD_NUMBER, dimension);

        turnScreenOff();

        uploadConfig(config);

        Thread.sleep(WAIT_TIME_LONG);
        turnScreenOn();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> data = getGaugeMetricDataList();

        turnScreenOff();
        assertTrue(data.size() > 0);
        Atom atom = data.get(0);
        assertTrue(atom.getRemainingBatteryCapacity().hasChargeUAh());
        assertTrue(atom.getRemainingBatteryCapacity().getChargeUAh() > 0);
    }

    @RestrictedBuildTest
    public void testFullBatteryCapacity() throws Exception {
        if (!TESTS_ENABLED) {return;}
        StatsdConfig.Builder config = getPulledAndAnomalyConfig();
        FieldMatcher.Builder dimension = FieldMatcher.newBuilder()
                .setField(Atom.FULL_BATTERY_CAPACITY_FIELD_NUMBER)
                .addChild(FieldMatcher.newBuilder()
                        .setField(FullBatteryCapacity.CAPACITY_UAH_FIELD_NUMBER));
        addGaugeAtom(config, Atom.FULL_BATTERY_CAPACITY_FIELD_NUMBER, dimension);

        turnScreenOff();

        uploadConfig(config);

        Thread.sleep(WAIT_TIME_LONG);
        turnScreenOn();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> data = getGaugeMetricDataList();

        turnScreenOff();

        assertTrue(data.size() > 0);
        Atom atom = data.get(0);
        assertTrue(atom.getFullBatteryCapacity().hasCapacityUAh());
        assertTrue(atom.getFullBatteryCapacity().getCapacityUAh() > 0);
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

        Thread.sleep(WAIT_TIME_LONG);
        turnScreenOn();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> data = getGaugeMetricDataList();

        Atom atom = data.get(0);
        assertTrue(!atom.getKernelWakelock().getName().equals(""));
        assertTrue(atom.getKernelWakelock().hasCount());
        assertTrue(atom.getKernelWakelock().hasVersion());
        assertTrue(atom.getKernelWakelock().getVersion() > 0);
        assertTrue(atom.getKernelWakelock().hasTime());
    }

    public void testCpuTimePerFreq() throws Exception {
        if (!TESTS_ENABLED) {return;}
        StatsdConfig.Builder config = getPulledAndAnomalyConfig();
        FieldMatcher.Builder dimension = FieldMatcher.newBuilder()
                .setField(Atom.CPU_TIME_PER_FREQ_FIELD_NUMBER)
                .addChild(FieldMatcher.newBuilder()
                        .setField(CpuTimePerFreq.CLUSTER_FIELD_NUMBER));
        addGaugeAtom(config, Atom.CPU_TIME_PER_FREQ_FIELD_NUMBER, dimension);

        turnScreenOff();

        uploadConfig(config);

        Thread.sleep(2000);
        turnScreenOn();
        Thread.sleep(2000);

        List<Atom> data = getGaugeMetricDataList();

        Atom atom = data.get(0);
        assertTrue(atom.getCpuTimePerFreq().getCluster() >= 0);
        assertTrue(atom.getCpuTimePerFreq().getFreqIndex() >= 0);
        assertTrue(atom.getCpuTimePerFreq().getTimeMs() > 0);
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

        Thread.sleep(WAIT_TIME_LONG);
        turnScreenOn();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> dataList = getGaugeMetricDataList();

        for (Atom atom: dataList) {
            assertTrue(!atom.getSubsystemSleepState().getSubsystemName().equals(""));
            assertTrue(atom.getSubsystemSleepState().getCount() >= 0);
            assertTrue(atom.getSubsystemSleepState().getTimeMs() >= 0);
        }
    }

    public void testModemActivityInfo() throws Exception {
        if (!TESTS_ENABLED) {return;}
        if (!hasFeature(FEATURE_TELEPHONY, true)) return;
        StatsdConfig.Builder config = getPulledAndAnomalyConfig();
        addGaugeAtom(config, Atom.MODEM_ACTIVITY_INFO_FIELD_NUMBER, null);

        turnScreenOff();

        uploadConfig(config);

        Thread.sleep(WAIT_TIME_LONG);
        turnScreenOn();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> dataList = getGaugeMetricDataList();

        for (Atom atom: dataList) {
            assertTrue(atom.getModemActivityInfo().getTimestampMs() > 0);
            assertTrue(atom.getModemActivityInfo().getSleepTimeMs() > 0);
            assertTrue(atom.getModemActivityInfo().getControllerIdleTimeMs() > 0);
            assertTrue(atom.getModemActivityInfo().getControllerTxTimePl0Ms() >= 0);
            assertTrue(atom.getModemActivityInfo().getControllerRxTimeMs() >= 0);
            assertTrue(atom.getModemActivityInfo().getEnergyUsed() >= 0);
        }
    }

    public void testWifiActivityInfo() throws Exception {
        if (!TESTS_ENABLED) {return;}
        if (!hasFeature(FEATURE_WIFI, true)) return;
        StatsdConfig.Builder config = getPulledAndAnomalyConfig();
        addGaugeAtom(config, Atom.WIFI_ACTIVITY_ENERGY_INFO_FIELD_NUMBER, null);

        turnScreenOff();

        uploadConfig(config);

        Thread.sleep(WAIT_TIME_LONG);
        turnScreenOn();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> dataList = getGaugeMetricDataList();

        for (Atom atom: dataList) {
            assertTrue(atom.getWifiActivityEnergyInfo().getTimestampMs() > 0);
            assertTrue(atom.getWifiActivityEnergyInfo().getStackState() >= 0);
            assertTrue(atom.getWifiActivityEnergyInfo().getControllerIdleTimeMs() > 0);
            assertTrue(atom.getWifiActivityEnergyInfo().getControllerTxTimeMs() >= 0);
            assertTrue(atom.getWifiActivityEnergyInfo().getControllerRxTimeMs() >= 0);
            assertTrue(atom.getWifiActivityEnergyInfo().getControllerEnergyUsed() >= 0);
        }
    }

    public void testBluetoothActivityInfo() throws Exception {
        if (!TESTS_ENABLED) {return;}
        if (!hasFeature(FEATURE_BLUETOOTH, true)) return;
        StatsdConfig.Builder config = getPulledAndAnomalyConfig();
        addGaugeAtom(config, Atom.BLUETOOTH_ACTIVITY_INFO_FIELD_NUMBER, null);

        turnScreenOff();

        uploadConfig(config);

        Thread.sleep(WAIT_TIME_LONG);
        turnScreenOn();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> dataList = getGaugeMetricDataList();

        for (Atom atom: dataList) {
            assertTrue(atom.getBluetoothActivityInfo().getTimestampMs() > 0);
            assertTrue(atom.getBluetoothActivityInfo().getBluetoothStackState() >= 0);
            assertTrue(atom.getBluetoothActivityInfo().getControllerIdleTimeMs() > 0);
            assertTrue(atom.getBluetoothActivityInfo().getControllerTxTimeMs() >= 0);
            assertTrue(atom.getBluetoothActivityInfo().getControllerRxTimeMs() >= 0);
            assertTrue(atom.getBluetoothActivityInfo().getEnergyUsed() >= 0);
        }
    }
}