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
import com.android.internal.os.StatsdConfigProto.KeyMatcher;
import com.android.internal.os.StatsdConfigProto.KeyValueMatcher;
import com.android.internal.os.StatsdConfigProto.SimpleAtomMatcher;
import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.os.StatsLog.ConfigMetricsReportList;
import com.android.os.StatsLog.EventMetricData;
import com.android.tradefed.log.LogUtil;

import com.google.common.io.Files;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Base class for testing Statsd atoms.
 * Validates reporting of statsd logging based on different events
 */
public class AtomTestCase extends BaseTestCase {

    private static final String UPDATE_CONFIG_CMD = "cmd stats config update";
    private static final String DUMP_REPORT_CMD = "cmd stats dump-report";
    private static final String REMOVE_CONFIG_CMD = "cmd stats config remove";
    protected static final String CONFIG_UID = "1000";
    protected static final String CONFIG_NAME = "cts_config";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // TODO: need to do these before running real test:
        // 1. compile statsd and push to device
        // 2. make sure StatsCompanionService and incidentd is running
        // 3. start statsd
        // These should go away once we have statsd properly set up.

        // Uninstall to clear the history in case it's still on the device.
        removeConfig("fake");
        removeConfig(CONFIG_NAME);
    }

    @Override
    protected void tearDown() throws Exception {
        removeConfig(CONFIG_NAME);
        super.tearDown();
    }

    /**
     * Determines whether logcat indicates that incidentd fired since the given device date.
     */
    protected boolean didIncidentdFireSince(String date) throws Exception {
        final String INCIDENTD_TAG = "incidentd";
        final String INCIDENTD_STARTED_STRING = "reportIncident";
        // TODO: Do something more robust than this in case of delayed logging.
        Thread.sleep(1000);
        String log = getLogcatSince(date, String.format(
                "-s %s -e %s", INCIDENTD_TAG, INCIDENTD_STARTED_STRING));
        return log.contains(INCIDENTD_STARTED_STRING);
    }

    protected void uploadConfig(StatsdConfig.Builder config) throws Exception {
        uploadConfig(config.build());
    }

    protected void uploadConfig(StatsdConfig config) throws Exception {
        File configFile = File.createTempFile("statsdconfig", ".config");
        Files.write(config.toByteArray(), configFile);
        String remotePath = "/data/" + configFile.getName();
        getDevice().pushFile(configFile, remotePath);
        getDevice().executeShellCommand(
                String.join(" ", "cat", remotePath, "|", UPDATE_CONFIG_CMD, CONFIG_UID,
                        CONFIG_NAME));
        getDevice().executeShellCommand("rm " + remotePath);
    }

    protected void removeConfig(String configName) throws Exception {
        getDevice().executeShellCommand(
                String.join(" ", REMOVE_CONFIG_CMD, CONFIG_UID, configName));
    }

    protected ConfigMetricsReportList getReportList() throws Exception {
        ConfigMetricsReportList reportList = getDump(ConfigMetricsReportList.parser(),
                String.join(" ", DUMP_REPORT_CMD, CONFIG_UID, CONFIG_NAME, "--proto"));
        LogUtil.CLog.d("get report list as following:\n" + reportList.toString());
        return reportList;
    }

    /** Creates a KeyValueMatcher.Builder corresponding to the given key. */
    protected static KeyValueMatcher.Builder createKvm(int key) {
        return KeyValueMatcher.newBuilder().setKeyMatcher(KeyMatcher.newBuilder().setKey(key));
    }

    /**
     * Adds an event to the config for an atom that matches the given key.
     * @param conf configuration
     * @param atomTag atom tag (from atoms.proto)
     * @param kvm KeyValueMatcher.Builder for the relevant key
     */
    protected void addAtomEvent(StatsdConfig.Builder conf, int atomTag, KeyValueMatcher.Builder kvm)
            throws Exception {
        addAtomEvent(conf, atomTag, Arrays.asList(kvm));
    }

    /**
     * Adds an event to the config for an atom that matches the given keys.
     * @param conf configuration
     * @param atomTag atom tag (from atoms.proto)
     * @param kvms list of KeyValueMatcher.Builders to attach to the atom. May be null.
     */
    protected void addAtomEvent(StatsdConfig.Builder conf, int atomTag,
            List<KeyValueMatcher.Builder> kvms) throws Exception {

        final String atomName = "Atom" + System.nanoTime();
        final String eventName = "Event" +  + System.nanoTime();

        SimpleAtomMatcher.Builder sam = SimpleAtomMatcher.newBuilder().setTag(atomTag);
        if (kvms != null) {
          for (KeyValueMatcher.Builder kvm : kvms) {
            sam.addKeyValueMatcher(kvm);
          }
        }
        conf.addAtomMatcher(AtomMatcher.newBuilder()
                .setName(atomName)
                .setSimpleAtomMatcher(sam));
        conf.addEventMetric(EventMetric.newBuilder()
                .setName(eventName)
                .setWhat(atomName));
    }

    protected void turnScreenOn() throws Exception {
        getDevice().executeShellCommand("input keyevent KEYCODE_WAKEUP");
        getDevice().executeShellCommand("wm dismiss-keyguard");
    }

    protected void turnScreenOff() throws Exception {
        getDevice().executeShellCommand("input keyevent KEYCODE_SLEEP");
    }

    protected void rebootDevice() throws Exception {
        getDevice().rebootUntilOnline();
    }

    /**
     * Determines whether the two events are within the specified range of each other.
     * @param d0 the event that should occur first
     * @param d1 the event that should occur second
     * @param minDiffMs d0 should precede d1 by at least this amount
     * @param maxDiffMs d0 should precede d1 by at most this amount
     * @return
     */
    public static boolean isTimeDiffBetween(EventMetricData d0, EventMetricData d1,
            int minDiffMs, int maxDiffMs) {
        long diffMs = (d1.getTimestampNanos() - d0.getTimestampNanos()) / 1_000_000;
        return minDiffMs <= diffMs && diffMs <= maxDiffMs;
    }

    protected String getCurrentLogcatDate() throws Exception {
        // TODO: Do something more robust than this for getting logcat markers.
        long timestampSecs = getDevice().getDeviceDate();
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS")
                .format(new Date(timestampSecs * 1000L));
    }

    protected String getLogcatSince(String date, String logcatParams) throws Exception {
        return getDevice().executeShellCommand(String.format(
                "logcat -v threadtime -t '%s' -d %s", date, logcatParams));
    }

    /**
     * Determines if the device has the given feature.
     * Prints a warning if its value differs from requiredAnswer.
     */
    protected boolean hasFeature(String featureName, boolean requiredAnswer) throws Exception {
        final String features = getDevice().executeShellCommand("pm list features");
        boolean hasIt = features.contains(featureName);
        if (hasIt != requiredAnswer) {
            LogUtil.CLog.w("Device does " + (requiredAnswer ? "not " : "") + "have feature "
                    + featureName);
        }
        return hasIt;
    }

}
