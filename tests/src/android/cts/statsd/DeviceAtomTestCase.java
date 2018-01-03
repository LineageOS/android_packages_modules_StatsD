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

import com.android.internal.os.StatsdConfigProto.AtomMatcher;
import com.android.internal.os.StatsdConfigProto.EventMetric;
import com.android.internal.os.StatsdConfigProto.FieldValueMatcher;
import com.android.internal.os.StatsdConfigProto.SimpleAtomMatcher;
import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.os.StatsLog.ConfigMetricsReport;
import com.android.os.StatsLog.ConfigMetricsReportList;
import com.android.os.StatsLog.EventMetricData;
import com.android.tradefed.log.LogUtil;

import java.util.Arrays;
import java.util.List;

/**
 * Base class for testing Statsd atoms that report a uid. Tests are performed via a device-side app.
 */
public class DeviceAtomTestCase extends AtomTestCase {

    protected static final String DEVICE_SIDE_TEST_APK = "CtsStatsdAtomsApp.apk";
    protected static final String DEVICE_SIDE_TEST_PACKAGE
            = "com.android.server.cts.device.statsd";

    protected static final String CONFIG_NAME = "cts_config";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        getDevice().uninstallPackage(DEVICE_SIDE_TEST_PACKAGE);
        installTestApp();
    }

    @Override
    protected void tearDown() throws Exception {
        getDevice().uninstallPackage(DEVICE_SIDE_TEST_PACKAGE);
        super.tearDown();
    }

    /**
     * Performs a device-side test by calling a method on the app and returns its stats events.
     * @param methodName the name of the method in the app's AtomTests to perform
     * @param atom atom tag (from atoms.proto)
     * @param key atom's field corresponding to state
     * @param stateOn 'on' value
     * @param stateOff 'off' value
     * @param minTimeDiffMs max allowed time between start and stop
     * @param maxTimeDiffMs min allowed time between start and stop
     * @param demandExactlyTwo whether there must be precisely two events logged (1 start, 1 stop)
     * @return list of events with the app's uid matching the configuration defined by the params.
     */
    protected List<EventMetricData> doDeviceMethodOnOff(
            String methodName, int atom, int key, int stateOn, int stateOff,
            int minTimeDiffMs, int maxTimeDiffMs, boolean demandExactlyTwo) throws Exception {
        StatsdConfig.Builder conf = createConfigBuilder();
        addAtomEvent(conf, atom, createKvm(key).setEqInt(stateOn));
        addAtomEvent(conf, atom, createKvm(key).setEqInt(stateOff));
        List<EventMetricData> data = doDeviceMethod(methodName, conf);

        if (demandExactlyTwo) {
            assertTrue(data.size() == 2);
        } else {
            assertTrue(data.size() >= 2);
        }
        assertTrue(isTimeDiffBetween(data.get(0), data.get(1), minTimeDiffMs, maxTimeDiffMs));
        return data;
    }

    /**
     *
     * @param methodName the name of the method in the app's AtomTests to perform
     * @param cfg statsd configuration
     * @return list of events with the app's uid matching the configuration.
     */
    protected List<EventMetricData> doDeviceMethod(String methodName, StatsdConfig.Builder cfg)
            throws Exception {
        removeConfig(CONFIG_ID);
        uploadConfig(cfg);
        int appUid = getUid();
        LogUtil.CLog.d("\nPerforming device-side test of " + methodName + " for uid " + appUid);
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", methodName);

        return getEventMetricDataList();
    }

    /**
     * Adds an event to the config for an atom that matches the given key AND has the app's uid.
     * @param conf configuration
     * @param atomTag atom tag (from atoms.proto)
     * @param kvm FieldValueMatcher.Builder for the relevant key
     */
    @Override
    protected void addAtomEvent(StatsdConfig.Builder conf, int atomTag, FieldValueMatcher.Builder kvm)
            throws Exception {

        final int UID_KEY = 1;
        FieldValueMatcher.Builder kvmUid = createKvm(UID_KEY).setEqInt(getUid());
        addAtomEvent(conf, atomTag, Arrays.asList(kvm, kvmUid));
    }

    /**
     * Gets the uid of the test app.
     */
    protected int getUid() throws Exception {
        String uidLine = getDevice().executeShellCommand("cmd package list packages -U "
                + DEVICE_SIDE_TEST_PACKAGE);
        String[] uidLineParts = uidLine.split(":");
        // 3rd entry is package uid
        assertTrue(uidLineParts.length > 2);
        int uid = Integer.parseInt(uidLineParts[2].trim());
        assertTrue(uid > 10000);
        return uid;
    }

    /**
     * Installs the test apk.
     */
    protected void installTestApp() throws Exception {
        installPackage(DEVICE_SIDE_TEST_APK, true);
        allowBackgroundServices();
    }

    /**
     * Required to successfully start a background service from adb in O.
     */
    protected void allowBackgroundServices() throws Exception {
        getDevice().executeShellCommand(String.format(
                "cmd deviceidle tempwhitelist %s", DEVICE_SIDE_TEST_PACKAGE));
    }
}
