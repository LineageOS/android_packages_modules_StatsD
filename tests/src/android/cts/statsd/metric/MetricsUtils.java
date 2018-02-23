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
package android.cts.statsd.metric;

import com.android.internal.os.StatsdConfigProto;
import com.android.internal.os.StatsdConfigProto.AtomMatcher;
import com.android.internal.os.StatsdConfigProto.FieldValueMatcher;
import com.android.internal.os.StatsdConfigProto.SimpleAtomMatcher;
import com.android.os.AtomsProto.Atom;
import com.android.os.AtomsProto.AppBreadcrumbReported;

public class MetricsUtils {
    public static final long COUNT_METRIC_ID = 3333;
    public static final long DURATION_METRIC_ID = 4444;
    public static final long GAUGE_METRIC_ID = 5555;
    public static final long VALUE_METRIC_ID = 6666;

    public static StatsdConfigProto.StatsdConfig.Builder getEmptyConfig() {
        StatsdConfigProto.StatsdConfig.Builder builder =
                StatsdConfigProto.StatsdConfig.newBuilder();
        // Only accept the log events from this cts and android system (1000) to avoid noise.
        builder.addAllowedLogSource("com.android.server.cts.device.statsd");
        builder.addAllowedLogSource("android");
        return builder;
    }

    public static AtomMatcher.Builder getAtomMatcher(int atomId) {
        AtomMatcher.Builder builder = AtomMatcher.newBuilder();
        builder.setSimpleAtomMatcher(SimpleAtomMatcher.newBuilder()
                        .setAtomId(atomId).build());
        return builder;
    }

    public static AtomMatcher startAtomMatcher(int id) {
      return AtomMatcher.newBuilder()
          .setId(id)
          .setSimpleAtomMatcher(
              SimpleAtomMatcher.newBuilder()
                  .setAtomId(Atom.APP_BREADCRUMB_REPORTED_FIELD_NUMBER)
                  .addFieldValueMatcher(FieldValueMatcher.newBuilder()
                                            .setField(AppBreadcrumbReported.STATE_FIELD_NUMBER)
                                            .setEqInt(AppBreadcrumbReported.State.START.ordinal())))
          .build();
    }

    public static AtomMatcher stopAtomMatcher(int id) {
      return AtomMatcher.newBuilder()
          .setId(id)
          .setSimpleAtomMatcher(
              SimpleAtomMatcher.newBuilder()
                  .setAtomId(Atom.APP_BREADCRUMB_REPORTED_FIELD_NUMBER)
                  .addFieldValueMatcher(FieldValueMatcher.newBuilder()
                                            .setField(AppBreadcrumbReported.STATE_FIELD_NUMBER)
                                            .setEqInt(AppBreadcrumbReported.State.STOP.ordinal())))
          .build();
    }

    public static AtomMatcher simpleAtomMatcher(int id) {
      return AtomMatcher.newBuilder()
          .setId(id)
          .setSimpleAtomMatcher(
              SimpleAtomMatcher.newBuilder().setAtomId(Atom.APP_BREADCRUMB_REPORTED_FIELD_NUMBER))
          .build();
    }

    public static long StringToId(String str) {
      return str.hashCode();
    }
}
